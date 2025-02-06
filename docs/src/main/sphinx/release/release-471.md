# Release 471 (19 Feb 2025)

## Delta Lake connector

* Add support for reading `variant` type. ({issue}`22309`)
* Support reading of `p`-type deletion vectors in Delta Lake. ({issue}`24946`)
* Increase compatibility with S3-compatible storage solutions. ({issue}`24954`)

## Hive connector

* Increase compatibility with S3-compatible storage solutions. ({issue}`24954`)
* Fix reading restored S3 glacier objects when the configuration property
  `hive.s3.storage-class-filter` is set to `READ_NON_GLACIER_AND_RESTORED`. ({issue}`24947`)

## Hudi connector

* Increase compatibility with S3-compatible storage solutions. ({issue}`24954`)

## Iceberg connector

* Add support for reading [S3
  Tables](https://aws.amazon.com/s3/features/tables/). ({issue}`24815`)
* Increase compatibility with S3-compatible storage solutions. ({issue}`24954`)
* Improve conflict detection to avoid failures from concurrent `MERGE INTO`
  queries on Iceberg tables. ({issue}`24470`)

## MongoDB connector

* Fix failure when tables exist with different cases. ({issue}`24998`)