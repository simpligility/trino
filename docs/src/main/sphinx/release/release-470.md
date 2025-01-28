# Release 470 (5 Feb 2025)

## General

* Add [](/connector/duckdb). ({issue}`18031`)
* Add Loki connector. ({issue}`23053`)
* Add support for access control systems to control privileges for
  `CREATE FUNCTION` and `DROP FUNCTION`. ({issue}`24696`)
* Fix failure when using upper-case variable names in SQL user-defined
  functions. ({issue}`24460`)
* Prevent failures of the `ARRAY_HISTOGRAM` function resulting from null values.
  ({issue}`24765`)
* Improve compatibility of fault tolerant exchange storage with S3-compliant
  object stores. ({issue}`24822`)

## Security

## Web UI

## JDBC driver

* {{breaking}} Raise minimum runtime requirement to Java 11. ({issue}`23639`)

## Server RPM

## Docker image

## CLI

* {{breaking}} Raise minimum runtime requirement to Java 11. ({issue}`23639`)

## BigQuery connector

## Blackhole connector

## Cassandra connector

## ClickHouse connector

## Delta Lake connector

* Prevent connection leakage when using the Azure file system. ({issue}`24116`)

## Druid connector

## Elasticsearch connector

## Exasol connector

## Faker connector

* Improve similarity of the fake data to the source data when using 
  `CREATE TABLE ... AS SELECT`. ({issue}`24585`)

## Google Sheets connector

## Hive connector

* Prevent connection leakage when using the Azure file system. ({issue}`24116`)

## Hudi connector

* Prevent connection leakage when using the Azure file system. ({issue}`24116`)

## Iceberg connector

* Allow configuration of the number of commit retries with the
  `max_commit_retry` table property. ({issue}`22672`)
* Allow Hive metastore caching. ({issue}`13115`)
* Prevent connection leakage when using the Azure file system. ({issue}`24116`)
* Fix failure when adding a new column with a name containing a dot. ({issue}`24813`)
* Fix failure when reading tables which equality deletes updated nested fields. ({issue}`18625`)

## Ignite connector

## JMX connector

## Kafka connector

## Kinesis connector

* {{breaking}} Remove the Kinesis connector. ({issue}`23923`) 

## Kudu connector

## MariaDB connector

## Memory connector

## MongoDB connector

## MySQL connector

* Add support for `MERGE` statement. ({issue}`24428`)
* Prevent writing of invalid, negative date values. ({issue}`24809`)

## OpenSearch connector

## Oracle connector

## Phoenix connector

## Pinot connector

## PostgreSQL connector

* Raise minimum required version to PostgreSQL 12. ({issue}`24836`)

## Prometheus connector

## Redis connector

## Redshift connector

## SingleStore connector

## Snowflake connector

## SQL Server connector

## TPC-H connector

## TPC-DS connector

## Vertica connector

## SPI
