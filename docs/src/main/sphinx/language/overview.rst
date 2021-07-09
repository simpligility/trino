=====================
SQL statement support
=====================

The SQL statement support in Trino can be categorized into a number of topics.
Numerous statements are part of the core engine and therefore available in all
use cases. On the other hand the details and architecture of the connected data
sources prevent some SQL functionality.

For example, if the data source does not support any write operations, Trino can
not implement support for SQL statements like :doc:`/sql/delete`. Similarly if
the underlying system does have any security concepts SQL statements like
:doc:`/sql/create-role` can not provided by Trino and the connector.

.. _sql-globally-available:

Globally available statements
-----------------------------

The following statements are implemented in the core engine and available with
any connector:

* :doc:`/sql/call`
* :doc:`/sql/deallocate-prepare`
* :doc:`/sql/describe-input`
* :doc:`/sql/describe-output`
* :doc:`/sql/execute`
* :doc:`/sql/explain`
* :doc:`/sql/explain-analyze`
* :doc:`/sql/prepare`
* :doc:`/sql/reset-session`
* :doc:`/sql/set-session`
* :doc:`/sql/set-time-zone`
* :doc:`/sql/show-functions`
* :doc:`/sql/show-session`
* :doc:`/sql/use`

.. _sql-read-operations:

Read operations
---------------

The following statements provide read access to data and meta data exposed by a
connector accessing a data source. They are available with most connectors:

* :doc:`/sql/select` including:

  * :doc:`/sql/match-recognize`
  * :doc:`/sql/values`

* :doc:`/sql/show-catalogs`
* :doc:`/sql/show-columns`
* :doc:`/sql/show-schemas`
* :doc:`/sql/show-tables`
* :doc:`/sql/show-stats`
* :doc:`/sql/describe`

.. _sql--write-operations:

Write operations
----------------

The following statements provide write access to data and meta data exposed by
by a connector plugin accessing a data source. Availability varies widely from
connector to connector:

* :doc:`/sql/alter-schema`
* :doc:`/sql/alter-table`
* :doc:`/sql/alter-view`
* :doc:`/sql/comment`
* :doc:`/sql/create-schema`
* CREATE MATERIALIZED VIEW
* :doc:`/sql/create-table-as`
* :doc:`/sql/create-table`
* :doc:`/sql/create-view`
* :doc:`/sql/delete`
* DROP MATERIALIZED VIEW
* :doc:`/sql/drop-schema`
* :doc:`/sql/drop-table`
* :doc:`/sql/drop-view`
* :doc:`/sql/insert`
* REFRESH MATERIALIZED VIEW
* SHOW CREATE MATERIALIZED VIEW
* :doc:`/sql/show-create-schema`
* :doc:`/sql/show-create-table`
* :doc:`/sql/show-create-view`
* :doc:`/sql/update`

.. _sql-security-operations:

Security operations
-------------------

The following statements provide security-related operations to security
configuration, data and meta data exposed by by a connector plugin accessing a
data source. Availability varies widely from connector to connector:

* :doc:`/sql/create-role`
* :doc:`/sql/drop-role`
* :doc:`/sql/grant-roles`
* :doc:`/sql/grant`
* :doc:`/sql/revoke-roles`
* :doc:`/sql/revoke`
* :doc:`/sql/set-role`
* :doc:`/sql/show-grants`
* :doc:`/sql/show-role-grants`
* :doc:`/sql/show-roles`

Transactions
------------

The following statements manage transactions. Availability varies widely from
connector to connector:

* :doc:`/sql/call`
* :doc:`/sql/commit`
* :doc:`/sql/rollback`
* :doc:`/sql/start-transaction`
