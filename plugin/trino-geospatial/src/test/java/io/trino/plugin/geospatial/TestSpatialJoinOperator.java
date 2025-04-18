/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.geospatial;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.RowPagesBuilder;
import io.trino.geospatial.KdbTree;
import io.trino.geospatial.KdbTreeUtils;
import io.trino.geospatial.Rectangle;
import io.trino.operator.Driver;
import io.trino.operator.DriverContext;
import io.trino.operator.Operator;
import io.trino.operator.OperatorFactory;
import io.trino.operator.PagesIndex.TestingFactory;
import io.trino.operator.PagesSpatialIndex;
import io.trino.operator.PagesSpatialIndexFactory;
import io.trino.operator.PipelineContext;
import io.trino.operator.SpatialIndexBuilderOperator.SpatialIndexBuilderOperatorFactory;
import io.trino.operator.SpatialIndexBuilderOperator.SpatialPredicate;
import io.trino.operator.SpatialJoinOperator.SpatialJoinOperatorFactory;
import io.trino.operator.TaskContext;
import io.trino.operator.ValuesOperator;
import io.trino.operator.join.InternalJoinFilterFunction;
import io.trino.operator.join.StandardJoinFilterFunction;
import io.trino.spi.Page;
import io.trino.spi.TrinoException;
import io.trino.sql.gen.JoinFilterFunctionCompiler;
import io.trino.sql.planner.plan.PlanNodeId;
import io.trino.sql.planner.plan.SpatialJoinNode.Type;
import io.trino.testing.MaterializedResult;
import io.trino.testing.TestingTaskContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.geospatial.KdbTree.Node.newInternal;
import static io.trino.geospatial.KdbTree.Node.newLeaf;
import static io.trino.operator.OperatorAssertion.assertOperatorEquals;
import static io.trino.operator.OperatorAssertion.toPages;
import static io.trino.plugin.geospatial.GeoFunctions.stGeometryFromText;
import static io.trino.plugin.geospatial.GeoFunctions.stPoint;
import static io.trino.plugin.geospatial.GeometryType.GEOMETRY;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.planner.plan.SpatialJoinNode.Type.INNER;
import static io.trino.sql.planner.plan.SpatialJoinNode.Type.LEFT;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static java.util.Collections.emptyIterator;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

@TestInstance(PER_METHOD)
public class TestSpatialJoinOperator
{
    private static final Slice KDB_TREE_JSON = KdbTreeUtils.toJson(
            new KdbTree(newInternal(new Rectangle(-2, -2, 15, 15),
                    newInternal(new Rectangle(-2, -2, 6, 15),
                            newLeaf(new Rectangle(-2, -2, 6, 1), 1),
                            newLeaf(new Rectangle(-2, 1, 6, 15), 2)),
                    newLeaf(new Rectangle(6, -2, 15, 15), 0))));

    //  2 intersecting polygons: A and B
    private static final Slice POLYGON_A = stGeometryFromText(Slices.utf8Slice("POLYGON ((0 0, -0.5 2.5, 0 5, 2.5 5.5, 5 5, 5.5 2.5, 5 0, 2.5 -0.5, 0 0))"));
    private static final Slice POLYGON_B = stGeometryFromText(Slices.utf8Slice("POLYGON ((4 4, 3.5 7, 4 10, 7 10.5, 10 10, 10.5 7, 10 4, 7 3.5, 4 4))"));

    // A set of points: X in A, Y in A and B, Z in B, W outside of A and B
    private static final Slice POINT_X = stPoint(1, 1);
    private static final Slice POINT_Y = stPoint(4.5, 4.5);
    private static final Slice POINT_Z = stPoint(6, 6);
    private static final Slice POINT_W = stPoint(20, 20);

    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    @BeforeEach
    public void setUp()
    {
        // Before/AfterMethod is chosen here because the executor needs to be shutdown
        // after every single test case to terminate outstanding threads, if any.

        // The line below is the same as newCachedThreadPool(daemonThreadsNamed(...)) except RejectionExecutionHandler.
        // RejectionExecutionHandler is set to DiscardPolicy (instead of the default AbortPolicy) here.
        // Otherwise, a large number of RejectedExecutionException will flood logging, resulting in Travis failure.
        executor = new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L,
                SECONDS,
                new SynchronousQueue<>(),
                daemonThreadsNamed("test-executor-%s"),
                new ThreadPoolExecutor.DiscardPolicy());
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed(getClass().getSimpleName() + "-scheduledExecutor-%s"));
    }

    @AfterEach
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void testSpatialJoin()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", "A")
                .row("y", "A")
                .row("y", "B")
                .row("z", "B")
                .build();

        assertSpatialJoin(taskContext, INNER, buildPages, probePages, expected);
    }

    @Test
    public void testSpatialLeftJoin()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");
        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", "A")
                .row("null", null)
                .row("y", "A")
                .row("y", "B")
                .row("z", "B")
                .row("w", null)
                .build();

        assertSpatialJoin(taskContext, LEFT, buildPages, probePages, expected);
    }

    private void assertSpatialJoin(TaskContext taskContext, Type joinType, RowPagesBuilder buildPages, RowPagesBuilder probePages, MaterializedResult expected)
    {
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, false).addDriverContext();
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.contains(probe), Optional.empty(), Optional.empty(), buildPages);
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), joinType, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);
        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    @Test
    public void testEmptyBuild()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR)).build();

        assertSpatialJoin(taskContext, INNER, buildPages, probePages, expected);
    }

    @Test
    public void testEmptyBuildLeftJoin()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", null)
                .row("null", null)
                .row("y", null)
                .row("z", null)
                .row("w", null)
                .build();

        assertSpatialJoin(taskContext, LEFT, buildPages, probePages, expected);
    }

    @Test
    public void testEmptyProbe()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR)).build();

        assertSpatialJoin(taskContext, INNER, buildPages, probePages, expected);
    }

    @Test
    public void testYield()
    {
        // create a filter function that yields for every probe match
        // verify we will yield #match times totally

        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, false).addDriverContext();

        // force a yield for every match
        AtomicInteger filterFunctionCalls = new AtomicInteger();
        InternalJoinFilterFunction filterFunction = new TestInternalJoinFilterFunction(
                (leftPosition, leftPage, rightPosition, rightPage) -> {
                    filterFunctionCalls.incrementAndGet();
                    driverContext.getYieldSignal().forceYieldForTesting();
                    return true;
                });

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .pageBreak()
                .row(POLYGON_B, "B");
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.contains(probe), Optional.empty(), Optional.of(filterFunction), buildPages);

        // 10 points in polygon A (x0...x9)
        // 10 points in polygons A and B (y0...y9)
        // 10 points in polygon B (z0...z9)
        // 40 total matches
        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(1 + 0.1 * i, 1 + 0.1 * i), "x" + i);
        }
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(4.5 + 0.01 * i, 4.5 + 0.01 * i), "y" + i);
        }
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(6 + 0.1 * i, 6 + 0.1 * i), "z" + i);
        }
        List<Page> probeInput = probePages.build();

        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);

        Operator operator = joinOperatorFactory.createOperator(driverContext);
        assertThat(operator.needsInput()).isTrue();
        operator.addInput(probeInput.get(0));
        operator.finish();

        // we will yield 40 times due to filterFunction
        for (int i = 0; i < 40; i++) {
            driverContext.getYieldSignal().setWithDelay(5 * SECONDS.toNanos(1), driverContext.getYieldExecutor());
            assertThat(operator.getOutput()).isNull();
            assertThat(filterFunctionCalls.get())
                    .describedAs("Expected join to stop processing (yield) after calling filter function once")
                    .isEqualTo(i + 1);
            driverContext.getYieldSignal().reset();
        }
        // delayed yield is not going to prevent operator from producing a page now (yield won't be forced because filter function won't be called anymore)
        driverContext.getYieldSignal().setWithDelay(5 * SECONDS.toNanos(1), driverContext.getYieldExecutor());
        Page output = operator.getOutput();
        assertThat(output).isNotNull();

        // make sure we have 40 matches
        assertThat(output.getPositionCount()).isEqualTo(40);
    }

    @Test
    public void testDuplicateProbeFactory()
            throws Exception
    {
        testDuplicateProbeFactory(true);
        testDuplicateProbeFactory(false);
    }

    public void testDuplicateProbeFactory(boolean createSecondaryOperators)
            throws Exception
    {
        TaskContext taskContext = createTaskContext();
        PipelineContext pipelineContext = taskContext.addPipelineContext(0, true, true, false);
        DriverContext probeDriver = pipelineContext.addDriverContext();
        DriverContext buildDriver = pipelineContext.addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, DOUBLE))
                .row(stPoint(0, 0), "0_0", 1.5);
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(buildDriver, (build, probe, r) -> build.distance(probe) <= r.getAsDouble(), Optional.of(2), Optional.empty(), buildPages);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(stPoint(0, 1), "0_1");
        OperatorFactory firstFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);

        for (int i = 0; i < 3; i++) {
            DriverContext secondDriver = pipelineContext.addDriverContext();
            OperatorFactory secondFactory = firstFactory.duplicate();
            if (createSecondaryOperators) {
                try (Operator secondOperator = secondFactory.createOperator(secondDriver)) {
                    assertThat(toPages(secondOperator, emptyIterator())).isEqualTo(ImmutableList.of());
                }
            }
            secondFactory.noMoreOperators();
        }

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("0_1", "0_0")
                .build();
        assertOperatorEquals(firstFactory, probeDriver, probePages.build(), expected);
    }

    @Test
    public void testDistanceQuery()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, false).addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, DOUBLE))
                .row(stPoint(0, 0), "0_0", 1.5)
                .row(null, "null", 1.5)
                .row(stPoint(1, 0), "1_0", 1.5)
                .pageBreak()
                .row(stPoint(3, 0), "3_0", 1.5)
                .pageBreak()
                .row(stPoint(10, 0), "10_0", 1.5);
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.distance(probe) <= r.getAsDouble(), Optional.of(2), Optional.empty(), buildPages);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(stPoint(0, 1), "0_1")
                .row(null, "null")
                .row(stPoint(1, 1), "1_1")
                .pageBreak()
                .row(stPoint(3, 1), "3_1")
                .pageBreak()
                .row(stPoint(10, 1), "10_1");
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("0_1", "0_0")
                .row("0_1", "1_0")
                .row("1_1", "0_0")
                .row("1_1", "1_0")
                .row("3_1", "3_0")
                .row("10_1", "10_0")
                .build();

        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    @Test
    public void testDistributedSpatialJoin()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, true).addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, INTEGER))
                .row(POLYGON_A, "A", 1)
                .row(POLYGON_A, "A", 2)
                .row(null, "null", null)
                .pageBreak()
                .row(POLYGON_B, "B", 0)
                .row(POLYGON_B, "B", 2);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, INTEGER))
                .row(POINT_X, "x", 2)
                .row(null, "null", null)
                .row(POINT_Y, "y", 2)
                .pageBreak()
                .row(POINT_Z, "z", 0);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", "A")
                .row("y", "A")
                .row("y", "B")
                .row("z", "B")
                .build();

        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.contains(probe), Optional.empty(), Optional.of(2), Optional.of(KDB_TREE_JSON), Optional.empty(), buildPages);
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, probePages.getTypes(), Ints.asList(1), 0, Optional.of(2), pagesSpatialIndexFactory);
        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    @Test
    public void testDistributedSpatialSelfJoin()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, true).addDriverContext();

        RowPagesBuilder pages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, INTEGER))
                .row(POLYGON_A, "A", 1)
                .row(POLYGON_A, "A", 2)
                .row(null, "null", null)
                .pageBreak()
                .row(POLYGON_B, "B", 0)
                .row(POLYGON_B, "B", 2);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("A", "A")
                .row("A", "B")
                .row("B", "A")
                .row("B", "B")
                .build();

        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.intersects(probe), Optional.empty(), Optional.of(2), Optional.of(KDB_TREE_JSON), Optional.empty(), pages);
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, pages.getTypes(), Ints.asList(1), 0, Optional.of(2), pagesSpatialIndexFactory);
        assertOperatorEquals(joinOperatorFactory, driverContext, pages.build(), expected);
    }

    private PagesSpatialIndexFactory buildIndex(DriverContext driverContext, SpatialPredicate spatialRelationshipTest, Optional<Integer> radiusChannel, Optional<InternalJoinFilterFunction> filterFunction, RowPagesBuilder buildPages)
    {
        return buildIndex(driverContext, spatialRelationshipTest, radiusChannel, Optional.empty(), Optional.empty(), filterFunction, buildPages);
    }

    private PagesSpatialIndexFactory buildIndex(DriverContext driverContext, SpatialPredicate spatialRelationshipTest, Optional<Integer> radiusChannel, Optional<Integer> partitionChannel, Optional<Slice> kdbTreeJson, Optional<InternalJoinFilterFunction> filterFunction, RowPagesBuilder buildPages)
    {
        Optional<JoinFilterFunctionCompiler.JoinFilterFunctionFactory> filterFunctionFactory = filterFunction
                .map(function -> (session, addresses, pages) -> new StandardJoinFilterFunction(function, addresses, pages));

        ValuesOperator.ValuesOperatorFactory valuesOperatorFactory = new ValuesOperator.ValuesOperatorFactory(0, new PlanNodeId("test"), buildPages.build());
        SpatialIndexBuilderOperatorFactory buildOperatorFactory = new SpatialIndexBuilderOperatorFactory(
                1,
                new PlanNodeId("test"),
                buildPages.getTypes(),
                Ints.asList(1),
                0,
                radiusChannel,
                OptionalDouble.empty(),
                partitionChannel,
                spatialRelationshipTest,
                kdbTreeJson,
                filterFunctionFactory,
                10_000,
                new TestingFactory(false));

        Driver driver = Driver.createDriver(driverContext,
                valuesOperatorFactory.createOperator(driverContext),
                buildOperatorFactory.createOperator(driverContext));

        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildOperatorFactory.getPagesSpatialIndexFactory();
        ListenableFuture<PagesSpatialIndex> pagesSpatialIndex = pagesSpatialIndexFactory.createPagesSpatialIndex();

        while (!pagesSpatialIndex.isDone()) {
            driver.processUntilBlocked();
        }

        runDriverInThread(executor, driver);
        return pagesSpatialIndexFactory;
    }

    /**
     * Runs Driver in another thread until it is finished
     */
    private static void runDriverInThread(ExecutorService executor, Driver driver)
    {
        executor.execute(() -> {
            if (!driver.isFinished()) {
                try {
                    driver.processUntilBlocked();
                }
                catch (TrinoException e) {
                    driver.getDriverContext().failed(e);
                    throw e;
                }
                runDriverInThread(executor, driver);
            }
        });
    }

    private TaskContext createTaskContext()
    {
        return TestingTaskContext.createTaskContext(executor, scheduledExecutor, TEST_SESSION);
    }

    private static class TestInternalJoinFilterFunction
            implements InternalJoinFilterFunction
    {
        public interface Lambda
        {
            boolean filter(int leftPosition, Page leftPage, int rightPosition, Page rightPage);
        }

        private final TestInternalJoinFilterFunction.Lambda lambda;

        private TestInternalJoinFilterFunction(TestInternalJoinFilterFunction.Lambda lambda)
        {
            this.lambda = lambda;
        }

        @Override
        public boolean filter(int leftPosition, Page leftPage, int rightPosition, Page rightPage)
        {
            return lambda.filter(leftPosition, leftPage, rightPosition, rightPage);
        }
    }
}
