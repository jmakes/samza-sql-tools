package com.linkedin.samza.tools;

import com.google.common.base.Joiner;
import com.linkedin.samza.tools.avro.AvroSchemaGenRelConverterFactory;
import com.linkedin.samza.tools.avro.AvroSerDeFactory;
import com.linkedin.samza.tools.json.JsonRelConverterFactory;
import com.linkedin.samza.tools.schemas.PageViewEvent;
import com.linkedin.samza.tools.schemas.ProfileChangeEvent;
import com.linkedin.samza.tools.schemas.SimpleRecord;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.samza.config.JobConfig;
import org.apache.samza.config.JobCoordinatorConfig;
import org.apache.samza.config.MapConfig;
import org.apache.samza.config.TaskConfig;
import org.apache.samza.container.grouper.task.SingleContainerGrouperFactory;
import org.apache.samza.serializers.StringSerdeFactory;
import org.apache.samza.sql.avro.ConfigBasedAvroRelSchemaProviderFactory;
import org.apache.samza.sql.fn.FlattenUdf;
import org.apache.samza.sql.impl.ConfigBasedSourceResolverFactory;
import org.apache.samza.sql.impl.ConfigBasedUdfResolver;
import org.apache.samza.sql.interfaces.SqlSystemStreamConfig;
import org.apache.samza.sql.runner.SamzaSqlApplicationConfig;
import org.apache.samza.sql.runner.SamzaSqlApplicationRunner;
import org.apache.samza.sql.testutil.JsonUtil;
import org.apache.samza.sql.testutil.SqlFileParser;
import org.apache.samza.standalone.PassthroughJobCoordinatorFactory;
import org.apache.samza.system.eventhub.EventHubSystemFactory;
import org.apache.samza.system.kafka.KafkaSystemFactory;


public class SamzaSqlConsole {

  private static final String OPT_SHORT_SQL_FILE = "f";
  private static final String OPT_LONG_SQL_FILE = "file";
  private static final String OPT_ARG_SQL_FILE = "SQL_FILE";
  private static final String OPT_DESC_SQL_FILE = "Path to the SQL file to execute.";

  private static final String OPT_SHORT_SQL_STMT = "s";
  private static final String OPT_LONG_SQL_STMT = "sql";
  private static final String OPT_ARG_SQL_STMT = "SQL_STMT";
  private static final String OPT_DESC_SQL_STMT = "SQL statement to execute.";

  private static final String SAMZA_SYSTEM_KAFKA = "kafka";
  private static final String SAMZA_SYSTEM_LOG = "log";
  private static final String SAMZA_SYSTEM_EVENTHUBS = "eh";

  public static void main(String[] args) {
    Options options = new Options();
    options.addOption(
        Utils.createOption(OPT_SHORT_SQL_FILE, OPT_LONG_SQL_FILE, OPT_ARG_SQL_FILE, false, OPT_DESC_SQL_FILE));
    options.addOption(
        Utils.createOption(OPT_SHORT_SQL_STMT, OPT_LONG_SQL_STMT, OPT_ARG_SQL_STMT, false, OPT_DESC_SQL_STMT));

    CommandLineParser parser = new BasicParser();
    CommandLine cmd;
    try {
      cmd = parser.parse(options, args);
      if (!cmd.hasOption(OPT_SHORT_SQL_STMT) && !cmd.hasOption(OPT_SHORT_SQL_FILE)) {
        throw new Exception(
            String.format("One of the (%s or %s) options needs to be set", OPT_SHORT_SQL_FILE, OPT_SHORT_SQL_STMT));
      }
    } catch (Exception e) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp(String.format("Error: %s%nsamza-sql-console.sh", e.getMessage()), options);
      return;
    }

    List<String> sqlStmts;

    if (cmd.hasOption(OPT_SHORT_SQL_FILE)) {
      String sqlFile = cmd.getOptionValue(OPT_SHORT_SQL_FILE);
      sqlStmts = SqlFileParser.parseSqlFile(sqlFile);
    } else {
      String sql = cmd.getOptionValue(OPT_SHORT_SQL_STMT);
      System.out.println("Executing sql " + sql);
      sqlStmts = Collections.singletonList(sql);
    }

    executeSql(sqlStmts);
  }

  public static void executeSql(List<String> sqlStmts) {
    Map<String, String> staticConfigs = fetchSamzaSqlConfig();
    staticConfigs.put(SamzaSqlApplicationConfig.CFG_SQL_STMTS_JSON, JsonUtil.toJson(sqlStmts));
    SamzaSqlApplicationRunner runner = new SamzaSqlApplicationRunner(true, new MapConfig(staticConfigs));
    runner.runAndWaitForFinish();
  }

  public static Map<String, String> fetchSamzaSqlConfig() {
    HashMap<String, String> staticConfigs = new HashMap<>();

    staticConfigs.put(JobConfig.JOB_NAME(), "sql-job");
    staticConfigs.put(JobConfig.PROCESSOR_ID(), "1");
    staticConfigs.put(JobCoordinatorConfig.JOB_COORDINATOR_FACTORY, PassthroughJobCoordinatorFactory.class.getName());
    staticConfigs.put(TaskConfig.GROUPER_FACTORY(), SingleContainerGrouperFactory.class.getName());

    staticConfigs.put(SamzaSqlApplicationConfig.CFG_SOURCE_RESOLVER, "config");
    String configSourceResolverDomain =
        String.format(SamzaSqlApplicationConfig.CFG_FMT_SOURCE_RESOLVER_DOMAIN, "config");
    staticConfigs.put(configSourceResolverDomain + SamzaSqlApplicationConfig.CFG_FACTORY,
        ConfigBasedSourceResolverFactory.class.getName());

    staticConfigs.put(SamzaSqlApplicationConfig.CFG_UDF_RESOLVER, "config");
    String configUdfResolverDomain = String.format(SamzaSqlApplicationConfig.CFG_FMT_UDF_RESOLVER_DOMAIN, "config");
    staticConfigs.put(configUdfResolverDomain + SamzaSqlApplicationConfig.CFG_FACTORY,
        ConfigBasedUdfResolver.class.getName());
    staticConfigs.put(configUdfResolverDomain + ConfigBasedUdfResolver.CFG_UDF_CLASSES,
        Joiner.on(",").join(StringContainsUdf.class.getName(), FlattenUdf.class.getName()));

    staticConfigs.put("serializers.registry.string.class", StringSerdeFactory.class.getName());
    staticConfigs.put("serializers.registry.avro.class", AvroSerDeFactory.class.getName());
    staticConfigs.put(AvroSerDeFactory.CFG_AVRO_SCHEMA, PageViewEvent.SCHEMA$.toString());

    String kafkaSystemConfigPrefix =
        String.format(ConfigBasedSourceResolverFactory.CFG_FMT_SAMZA_PREFIX, SAMZA_SYSTEM_KAFKA);
    String avroSamzaSqlConfigPrefix = configSourceResolverDomain + String.format("%s.", SAMZA_SYSTEM_KAFKA);
    staticConfigs.put(kafkaSystemConfigPrefix + "samza.factory", KafkaSystemFactory.class.getName());
    staticConfigs.put(kafkaSystemConfigPrefix + "samza.key.serde", "string");
    staticConfigs.put(kafkaSystemConfigPrefix + "samza.msg.serde", "avro");
    staticConfigs.put(kafkaSystemConfigPrefix + "consumer.zookeeper.connect", "localhost:2181");
    staticConfigs.put(kafkaSystemConfigPrefix + "producer.bootstrap.servers", "localhost:9092");

    staticConfigs.put(kafkaSystemConfigPrefix + "samza.offset.reset", "true");
    staticConfigs.put(kafkaSystemConfigPrefix + "samza.offset.default", "oldest");

    staticConfigs.put(avroSamzaSqlConfigPrefix + SqlSystemStreamConfig.CFG_SAMZA_REL_CONVERTER, "avro");
    staticConfigs.put(avroSamzaSqlConfigPrefix + SqlSystemStreamConfig.CFG_REL_SCHEMA_PROVIDER, "config");

    String ehSystemConfigPrefix =
        String.format(ConfigBasedSourceResolverFactory.CFG_FMT_SAMZA_PREFIX, SAMZA_SYSTEM_EVENTHUBS);
    String ehSamzaSqlConfigPrefix = configSourceResolverDomain + String.format("%s.", SAMZA_SYSTEM_EVENTHUBS);
    staticConfigs.put(ehSystemConfigPrefix + "samza.factory", EventHubSystemFactory.class.getName());
    staticConfigs.put(ehSystemConfigPrefix + "stream.list", "OutputStream");
    staticConfigs.put(ehSystemConfigPrefix + "streams.OutputStream.eventhubs.namespace", "srinieh1");
    staticConfigs.put(ehSystemConfigPrefix + "streams.OutputStream.eventhubs.entitypath", "OutputStream");
    staticConfigs.put(ehSystemConfigPrefix + "streams.OutputStream.eventhubs.sas.keyname", "WriteKey");
    staticConfigs.put(ehSystemConfigPrefix + "streams.OutputStream.eventhubs.sas.token",
        "BFMZOHEBLbukDJcuMrx1S9HjxjjUW3feXuuc4fhD7oA=");

    staticConfigs.put(ehSamzaSqlConfigPrefix + SqlSystemStreamConfig.CFG_SAMZA_REL_CONVERTER, "json");
    staticConfigs.put(ehSamzaSqlConfigPrefix + SqlSystemStreamConfig.CFG_REL_SCHEMA_PROVIDER, "config");

    String logSystemConfigPrefix =
        String.format(ConfigBasedSourceResolverFactory.CFG_FMT_SAMZA_PREFIX, SAMZA_SYSTEM_LOG);
    String logSamzaSqlConfigPrefix = configSourceResolverDomain + String.format("%s.", SAMZA_SYSTEM_LOG);
    staticConfigs.put(logSystemConfigPrefix + "samza.factory", ConsoleLoggingSystemFactory.class.getName());
    staticConfigs.put(logSamzaSqlConfigPrefix + SqlSystemStreamConfig.CFG_SAMZA_REL_CONVERTER, "json");
    staticConfigs.put(logSamzaSqlConfigPrefix + SqlSystemStreamConfig.CFG_REL_SCHEMA_PROVIDER, "config");

    String avroSamzaToRelMsgConverterDomain =
        String.format(SamzaSqlApplicationConfig.CFG_FMT_SAMZA_REL_CONVERTER_DOMAIN, "avro");
//    staticConfigs.put(avroSamzaToRelMsgConverterDomain + SamzaSqlApplicationConfig.CFG_FACTORY,
//        AvroRelConverterFactory.class.getName());

    staticConfigs.put(avroSamzaToRelMsgConverterDomain + SamzaSqlApplicationConfig.CFG_FACTORY,
        AvroSchemaGenRelConverterFactory.class.getName());

    String jsonSamzaToRelMsgConverterDomain =
        String.format(SamzaSqlApplicationConfig.CFG_FMT_SAMZA_REL_CONVERTER_DOMAIN, "json");

    staticConfigs.put(jsonSamzaToRelMsgConverterDomain + SamzaSqlApplicationConfig.CFG_FACTORY,
        JsonRelConverterFactory.class.getName());

    String configAvroRelSchemaProviderDomain =
        String.format(SamzaSqlApplicationConfig.CFG_FMT_REL_SCHEMA_PROVIDER_DOMAIN, "config");
    staticConfigs.put(configAvroRelSchemaProviderDomain + SamzaSqlApplicationConfig.CFG_FACTORY,
        ConfigBasedAvroRelSchemaProviderFactory.class.getName());

    staticConfigs.put(
        configAvroRelSchemaProviderDomain + String.format(ConfigBasedAvroRelSchemaProviderFactory.CFG_SOURCE_SCHEMA,
            "kafka", "PageViewStream"), PageViewEvent.SCHEMA$.toString());

    staticConfigs.put(
        configAvroRelSchemaProviderDomain + String.format(ConfigBasedAvroRelSchemaProviderFactory.CFG_SOURCE_SCHEMA,
            "kafka", "out"), SimpleRecord.SCHEMA$.toString());

    staticConfigs.put(
        configAvroRelSchemaProviderDomain + String.format(ConfigBasedAvroRelSchemaProviderFactory.CFG_SOURCE_SCHEMA,
            "log", "outputStream"), SimpleRecord.SCHEMA$.toString());

    staticConfigs.put(
        configAvroRelSchemaProviderDomain + String.format(ConfigBasedAvroRelSchemaProviderFactory.CFG_SOURCE_SCHEMA,
            "kafka", "ProfileChangeStream"), ProfileChangeEvent.SCHEMA$.toString());

    return staticConfigs;
  }
}
