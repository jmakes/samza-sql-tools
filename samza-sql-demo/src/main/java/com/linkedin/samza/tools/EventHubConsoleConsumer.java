package com.linkedin.samza.tools;

import com.microsoft.azure.eventhubs.EventData;
import com.microsoft.azure.eventhubs.EventHubClient;
import com.microsoft.azure.eventhubs.EventHubRuntimeInformation;
import com.microsoft.azure.eventhubs.PartitionReceiver;
import com.microsoft.azure.servicebus.ConnectionStringBuilder;
import com.microsoft.azure.servicebus.ServiceBusException;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;


public class EventHubConsoleConsumer {

  private static final String OPT_SHORT_EVENTHUB_NAME = "e";
  private static final String OPT_LONG_EVENTHUB_NAME = "ehname";
  private static final String OPT_ARG_EVENTHUB_NAME = "EVENTHUB_NAME";
  private static final String OPT_DESC_EVENTHUB_NAME = "Name of the event hub.";

  private static final String OPT_SHORT_NAMESPACE = "n";
  private static final String OPT_LONG_NAMESPACE = "namespace";
  private static final String OPT_ARG_NAMESPACE = "EVENTHUB_NAMESPACE";
  private static final String OPT_DESC_NAMESPACE = "Namespace of the event hub.";

  private static final String OPT_SHORT_KEY_NAME = "k";
  private static final String OPT_LONG_KEY_NAME = "key";
  private static final String OPT_ARG_KEY_NAME = "KEY_NAME";
  private static final String OPT_DESC_KEY_NAME = "Name of the key.";

  private static final String OPT_SHORT_TOKEN = "t";
  private static final String OPT_LONG_TOKEN = "token";
  private static final String OPT_ARG_TOKEN = "TOKEN";
  private static final String OPT_DESC_TOKEN = "Token corresponding to the key.";

  public static void main(String[] args)
      throws ServiceBusException, IOException, ExecutionException, InterruptedException {
    Options options = new Options();
    options.addOption(Utils.createOption(OPT_SHORT_EVENTHUB_NAME, OPT_LONG_EVENTHUB_NAME, OPT_ARG_EVENTHUB_NAME, true,
        OPT_DESC_EVENTHUB_NAME));

    options.addOption(
        Utils.createOption(OPT_SHORT_NAMESPACE, OPT_LONG_NAMESPACE, OPT_ARG_NAMESPACE, true, OPT_DESC_NAMESPACE));

    options.addOption(
        Utils.createOption(OPT_SHORT_KEY_NAME, OPT_LONG_KEY_NAME, OPT_ARG_KEY_NAME, true, OPT_DESC_KEY_NAME));

    options.addOption(Utils.createOption(OPT_SHORT_TOKEN, OPT_LONG_TOKEN, OPT_ARG_TOKEN, true, OPT_DESC_TOKEN));

    CommandLineParser parser = new BasicParser();
    CommandLine cmd;
    try {
      cmd = parser.parse(options, args);
    } catch (Exception e) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp(String.format("Error: %s%neh-console-consumer.sh", e.getMessage()), options);
      return;
    }

    String ehName = cmd.getOptionValue(OPT_SHORT_EVENTHUB_NAME);
    String namespace = cmd.getOptionValue(OPT_SHORT_NAMESPACE);
    String keyName = cmd.getOptionValue(OPT_SHORT_KEY_NAME);
    String token = cmd.getOptionValue(OPT_SHORT_TOKEN);

    consumeEvents(ehName, namespace, keyName, token);
  }

  private static void consumeEvents(String ehName, String namespace, String keyName, String token)
      throws ServiceBusException, IOException, ExecutionException, InterruptedException {
    ConnectionStringBuilder connStr = new ConnectionStringBuilder(namespace, ehName, keyName, token);

    EventHubClient client = EventHubClient.createFromConnectionStringSync(connStr.toString());

    EventHubRuntimeInformation runTimeInfo = client.getRuntimeInformation().get();
    int numPartitions = runTimeInfo.getPartitionCount();
    for (int partition = 0; partition < numPartitions; partition++) {
      PartitionReceiver receiver =
          client.createReceiverSync(EventHubClient.DEFAULT_CONSUMER_GROUP_NAME, String.valueOf(partition),
              PartitionReceiver.START_OF_STREAM);
      receiver.receive(10).handle((records, throwable) -> handleComplete(receiver, records, throwable));
    }
  }

  private static Object handleComplete(PartitionReceiver receiver, Iterable<EventData> records, Throwable throwable) {
    for (EventData record : records) {
      System.out.println(
          String.format("Partition %s, Event %s", receiver.getPartitionId(), new String(record.getBytes())));
    }

    receiver.receive(10).handle((r, t) -> handleComplete(receiver, r, t));
    return null;
  }
}
