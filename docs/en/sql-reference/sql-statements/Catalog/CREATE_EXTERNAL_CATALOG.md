---
displayed_sidebar: docs
---

# CREATE EXTERNAL CATALOG

CREATE EXTERNAL CATALOG creates an external catalog. You can use external catalogs to query data in external data sources without loading data into StarRocks or creating external tables. Currently, you can create the following types of external catalogs:

- [Hive catalog](../../../data_source/catalog/hive_catalog.md): used for querying data from Apache Hive™.
- [Iceberg catalog](../../../data_source/catalog/iceberg/iceberg_catalog.md): used for querying data from Apache Iceberg.
- [Hudi catalog](../../../data_source/catalog/hudi_catalog.md): used for querying data from Apache Hudi.
- [Delta Lake catalog](../../../data_source/catalog/deltalake_catalog.md): used to query data from Delta Lake.
- [JDBC catalog](../../../data_source/catalog/jdbc_catalog.md): used to query data from JDBC-compatible data sources.
- [Elasticsearch catalog](../../../data_source/catalog/elasticsearch_catalog.md): used to query data from Elasticsearch. Elasticsearch catalogs are supported from v3.1 onwards.
- [Paimon catalog](../../../data_source/catalog/paimon_catalog.md): used to query data from Apache Paimon. Paimon catalogs are supported from v3.1 onwards.
- [Unified catalog](../../../data_source/catalog/unified_catalog.md): used to query data from from Hive, Iceberg, Hudi, and Delta Lake data sources as a unified data source. Unified catalogs are supported from v3.2 onwards.

> **NOTE**
>
> - In v3.0 and later, this statement requires SYSTEM-level CREATE EXTERNAL CATALOG privilege.
> - Before you create external catalogs, configure your StarRocks cluster to meet the requirements of the data storage system (such as Amazon S3), metadata service (such as Hive metastore), and authenticating service (such as Kerberos) of external data sources. For more information, see the "Before you begin" section in each [external catalog topic](../../../data_source/catalog/catalog_overview.md).

## Syntax

```SQL
CREATE EXTERNAL CATALOG [IF NOT EXISTS] <catalog_name>
[COMMENT <comment>]
PROPERTIES ("key"="value", ...)
```

## Parameters

| **Parameter** | **Required** | **Description**                                              |
| ------------- | ------------ | ------------------------------------------------------------ |
| catalog_name  | Yes          | The name of the external catalog. For the naming conventions, see [System limits](../../System_limit.md). |
| comment       | No           | The description of the external catalog. |
| PROPERTIES    | Yes          | The properties of an external catalog. Configure properties based on the types of external catalogs. For more information, see [Hive catalog](../../../data_source/catalog/hive_catalog.md), [Iceberg catalog](../../../data_source/catalog/iceberg/iceberg_catalog.md), [Hudi catalog](../../../data_source/catalog/hudi_catalog.md), [Delta Lake catalog](../../../data_source/catalog/deltalake_catalog.md), and [JDBC Catalog](../../../data_source/catalog/jdbc_catalog.md). |

## Examples

Example 1: Create a Hive catalog named `hive_metastore_catalog`. The corresponding Hive cluster uses Hive metastore as its metadata service.

```SQL
CREATE EXTERNAL CATALOG hive_metastore_catalog
COMMENT "External catalog to Hive"
PROPERTIES(
   "type"="hive", 
   "hive.metastore.uris"="thrift://xx.xx.xx.xx:9083"
);
```

Example 2: Create a Hive catalog named `hive_glue_catalog`. The corresponding Hive cluster uses AWS Glue as its metadata service.

```SQL
CREATE EXTERNAL CATALOG hive_glue_catalog
COMMENT "External catalog to Hive"
PROPERTIES(
    "type"="hive", 
    "hive.metastore.type"="glue",
    "aws.hive.metastore.glue.aws-access-key"="xxxxxx",
    "aws.hive.metastore.glue.aws-secret-key"="xxxxxxxxxxxx",
    "aws.hive.metastore.glue.endpoint"="https://glue.x-x-x.amazonaws.com"
);
```

Example 3: Create an Iceberg catalog named `iceberg_metastore_catalog`. The corresponding Iceberg cluster uses Hive metastore as its metadata service.

```SQL
CREATE EXTERNAL CATALOG iceberg_metastore_catalog
COMMENT "External catalog to Iceberg"
PROPERTIES(
    "type"="iceberg",
    "iceberg.catalog.type"="hive",
    "iceberg.catalog.hive.metastore.uris"="thrift://xx.xx.xx.xx:9083"
);
```

Example 4: Create an Iceberg catalog named `iceberg_glue_catalog`. The corresponding Iceberg cluster uses AWS Glue as its metadata service.

```SQL
CREATE EXTERNAL CATALOG iceberg_glue_catalog
COMMENT "External catalog to Iceberg"
PROPERTIES(
    "type"="iceberg", 
    "iceberg.catalog.type"="glue",
    "aws.hive.metastore.glue.aws-access-key"="xxxxx",
    "aws.hive.metastore.glue.aws-secret-key"="xxxxxxxxxxxx",
    "aws.hive.metastore.glue.endpoint"="https://glue.x-x-x.amazonaws.com"
);
```

Example 5: Create a Hudi catalog named `hudi_metastore_catalog`. The corresponding Hudi cluster uses Hive metastore as its metadata service.

```SQL
CREATE EXTERNAL CATALOG hudi_metastore_catalog
COMMENT "External catalog to Hudi"
PROPERTIES(
    "type"="hudi",
    "hive.metastore.uris"="thrift://xx.xx.xx.xx:9083"
);
```

Example 6: Create a Hudi catalog named `hudi_glue_catalog`. The corresponding Hudi cluster uses AWS Glue as its metadata service.

```SQL
CREATE EXTERNAL CATALOG hudi_glue_catalog
COMMENT "External catalog to Hudi"
PROPERTIES(
    "type"="hudi", 
    "hive.metastore.type"="glue",
    "aws.hive.metastore.glue.aws-access-key"="xxxxxx",
    "aws.hive.metastore.glue.aws-secret-key"="xxxxxxxxxxxx",
    "aws.hive.metastore.glue.endpoint"="https://glue.x-x-x.amazonaws.com"
);
```

Example 7: Create a Delta Lake catalog named `delta_metastore_catalog`. The corresponding Delta Lake service uses Hive metastore as its metadata service.

```SQL
CREATE EXTERNAL CATALOG delta_metastore_catalog
COMMENT "External catalog to Delta"
PROPERTIES(
    "type"="deltalake",
    "hive.metastore.uris"="thrift://xx.xx.xx.xx:9083"
);
```

Example 8: Create a Delta Lake catalog named `delta_glue_catalog`. The corresponding Delta Lake service uses AWS Glue as its metadata service.

```SQL
CREATE EXTERNAL CATALOG delta_glue_catalog
COMMENT "External catalog to Delta"
PROPERTIES(
    "type"="deltalake", 
    "hive.metastore.type"="glue",
    "aws.hive.metastore.glue.aws-access-key"="xxxxxx",
    "aws.hive.metastore.glue.aws-secret-key"="xxxxxxxxxxxx",
    "aws.hive.metastore.glue.endpoint"="https://glue.x-x-x.amazonaws.com"
);
```

## References

- To view all catalogs in your StarRocks cluster, see [SHOW CATALOGS](SHOW_CATALOGS.md).
- To view the creation statement of an external catalog, see [SHOW CREATE CATALOG](SHOW_CREATE_CATALOG.md).
- To delete an external catalog from your StarRocks cluster, see [DROP CATALOG](DROP_CATALOG.md).
