========================
CREATE MATERIALIZED VIEW
========================

Synopsis
--------

.. code-block:: text

    CREATE [ OR REPLACE ] MATERIALIZED VIEW
    [ IF NOT EXISTS ] view_name
    AS query

    from Antlr file..
    CREATE (OR REPLACE)?  MATERIALIZED VIEW
    (IF NOT EXISTS)?
    qualifiedName
    (COMMENT string)?
    (WITH properties)? AS query

Description
-----------

TBD

Create a new view of a :doc:`select` query. The view is a logical table
that can be referenced by future queries. Views do not contain any data.
Instead, the query stored by the view is executed every time the view is
referenced by another query.

The optional ``OR REPLACE`` clause causes the view to be replaced if it
already exists rather than raising an error.

Examples
--------

TBD

Create a simple view ``test`` over the ``orders`` table::

    CREATE VIEW test AS
    SELECT orderkey, orderstatus, totalprice / 2 AS half
    FROM orders

Create a view ``orders_by_date`` that summarizes ``orders``::

    CREATE VIEW orders_by_date AS
    SELECT orderdate, sum(totalprice) AS price
    FROM orders
    GROUP BY orderdate

Create a view that replaces an existing view::

    CREATE OR REPLACE VIEW test AS
    SELECT orderkey, orderstatus, totalprice / 4 AS quarter
    FROM orders

See also
--------

* :doc:`drop-materialized-view`
* :doc:`show-create-materialized-view`
* :doc:`refresh-materialized-view`
