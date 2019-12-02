package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.impl.StringEscapeUtils
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.asyncsql.PostgreSQLClient
import io.vertx.ext.sql.SQLClient
import java.util.stream.Collectors

class PostgresVerticle : AbstractVerticle() {

  private lateinit var parameters: JsonObject
  private lateinit var sqlClient: SQLClient

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)

    val configStore = ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(JsonObject().put("path", "aware-config.json"))

    val configRetrieverOptions = ConfigRetrieverOptions()
      .addStore(configStore)
      .setScanPeriod(5000)

    val eventBus = vertx.eventBus()

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")

        val mysqlConfig = JsonObject()
          .put("host", serverConfig.getString("database_host"))
          .put("port", serverConfig.getInteger("database_port"))
          .put("database", serverConfig.getString("database_name"))
          .put("username", serverConfig.getString("database_user"))
          .put("password", serverConfig.getString("database_pwd"))
          .put("sslMode", "prefer")
          .put("sslRootCert", serverConfig.getString("path_fullchain_pem"))

        sqlClient = PostgreSQLClient.createShared(vertx, mysqlConfig)

        eventBus.consumer<String>("createTable") { receivedMessage ->
          val table = receivedMessage.body()
          createTable(table)
        }

        eventBus.consumer<JsonObject>("insertData") { receivedMessage ->
          val postData = receivedMessage.body()
          insertData(
            database = serverConfig.getString("database_name"),
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            data = JsonArray(postData.getString("data"))
          )
        }
      }
    }
  }

  /**
   * Check if table exists in database, create it if not present
   */
  fun tableExists(database: String, table: String) {
    sqlClient.getConnection {
      if (it.succeeded()) {
        val connection = it.result()
        connection.query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$database' and table_name='$table'") {
          if (it.succeeded()) {
            val resultSet = it.result()
            if (resultSet.numRows == 0)  {
              createTable(table)
            }
          }
        }
        connection.close()
      } else {
        println("Failed to establish connection to database: ${it.cause().message}")
      }
    }
  }

  fun createTable(table: String) {
    sqlClient.getConnection {
      if (it.succeeded()) {
        val connection = it.result()
        connection.query("CREATE TABLE IF NOT EXISTS `$table` (`_id` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY, `timestamp` DOUBLE NOT NULL, `device_id` VARCHAR(128) NOT NULL, `data` JSON NOT NULL, INDEX `timestamp_device` (`timestamp`, `device_id`))") {
          if (it.failed()) {
            println("Failed to create table: ${it.cause().message}")
            connection.close()
          } else {
            println("Table: $table [OK]")
            connection.close()
          }
        }
      } else {
        println("Failed to establish connection to PostgreSQL: ${it.cause().message}")
      }
    }
  }

  fun insertData(database: String, table: String, device_id: String, data: JsonArray) {
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {

        //Check if table exists before inserting
        tableExists(database, table)

        val connection = connectionResult.result()
        val rows = data.size()
        val values = ArrayList<String>()
        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)
          values.add("('$device_id', '${entry.getDouble("timestamp")}', '${StringEscapeUtils.escapeJavaScript(entry.encode())}')")
        }
        val insertBatch =
          "INSERT INTO `$table` (`device_id`,`timestamp`,`data`) VALUES ${values.stream().map(Any::toString).collect(
            Collectors.joining(",")
          )}"
        connection.query(insertBatch) { result ->
          if (result.failed()) {
            println("Failed to process batch: ${result.cause().message}")
            connection.close()
          } else {
            println("$device_id saved to $table: $rows records")
            connection.close()
          }
        }

      } else {
        println("Failed to establish connection to PostgreSQL: ${connectionResult.cause().message}")
      }
    }
  }

  override fun stop() {
    super.stop()
    println("AWARE Micro: PostgreSQL client shutdown")
    sqlClient.close()
  }
}
