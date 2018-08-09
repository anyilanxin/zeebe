/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.util;

import io.zeebe.broker.Broker;
import io.zeebe.broker.workflow.data.WorkflowInstanceRecord;
import io.zeebe.logstreams.impl.service.LogStreamServiceNames;
import io.zeebe.logstreams.log.BufferedLogStreamReader;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.log.LogStreamReader;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.protocol.clientapi.ValueType;
import io.zeebe.protocol.impl.RecordMetadata;
import io.zeebe.servicecontainer.Injector;
import io.zeebe.servicecontainer.Service;
import io.zeebe.servicecontainer.ServiceContainer;
import io.zeebe.servicecontainer.ServiceName;
import io.zeebe.servicecontainer.ServiceStartContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogStreamPrinter {

  private static final ServiceName<Object> PRINTER_SERVICE_NAME =
      ServiceName.newServiceName("printer", Object.class);

  private static final Logger LOGGER = LoggerFactory.getLogger("io.zeebe.broker.test");

  public static void printRecords(Broker broker, String topic, int partitionId) {
    try {
      final ServiceContainer serviceContainer = broker.getBrokerContext().getServiceContainer();

      final ServiceName<LogStream> logStreamServiceName =
          LogStreamServiceNames.logStreamServiceName(topic + "-" + partitionId);

      final PrinterService printerService = new PrinterService();
      serviceContainer
          .createService(PRINTER_SERVICE_NAME, printerService)
          .dependency(logStreamServiceName, printerService.getLogStreamInjector())
          .install()
          .join();

      serviceContainer.removeService(PRINTER_SERVICE_NAME).join();
    } catch (Exception e) {
      LOGGER.error(
          "Could not print log entries. This exception is not propagated to avoid interference with the test.",
          e);
    }
  }

  private static class PrinterService implements Service<Object> {

    private static final String HEADER_INDENTATION = "\t";
    private static final String ENTRY_INDENTATION = HEADER_INDENTATION + "\t";
    private Injector<LogStream> logStreamInjector = new Injector<>();

    @Override
    public Object get() {
      return this;
    }

    @Override
    public void start(ServiceStartContext startContext) {
      final LogStream logStream = logStreamInjector.getValue();

      final StringBuilder sb = new StringBuilder();
      sb.append("Records on partition ");
      sb.append(logStream.getPartitionId());
      sb.append(":\n");

      try (LogStreamReader streamReader = new BufferedLogStreamReader(logStream)) {
        streamReader.seekToFirstEvent();

        while (streamReader.hasNext()) {
          final LoggedEvent event = streamReader.next();
          writeRecord(event, sb);
        }
      }

      LOGGER.info(sb.toString());
    }

    private void writeRecord(final LoggedEvent event, final StringBuilder sb) {
      sb.append(HEADER_INDENTATION);
      writeRecordHeader(event, sb);
      sb.append("\n");
      final RecordMetadata metadata = new RecordMetadata();
      event.readMetadata(metadata);
      sb.append(ENTRY_INDENTATION);
      writeMetadata(metadata, sb);
      sb.append("\n");

      sb.append(ENTRY_INDENTATION);
      writeRecordBody(sb, event, metadata);
      sb.append("\n");
    }

    private void writeRecordBody(
        final StringBuilder sb, final LoggedEvent event, final RecordMetadata metadata) {
      if (metadata.getValueType() == ValueType.WORKFLOW_INSTANCE) {
        final WorkflowInstanceRecord record = new WorkflowInstanceRecord();
        event.readValue(record);
        writeWorkflowInstanceBody(record, sb);
      } else {
        sb.append("<value>");
      }
    }

    private void writeRecordHeader(LoggedEvent event, StringBuilder sb) {
      sb.append("Position: ");
      sb.append(event.getPosition());
    }

    private void writeWorkflowInstanceBody(WorkflowInstanceRecord record, StringBuilder sb) {
      record.writeJSON(sb);
    }

    private void writeMetadata(RecordMetadata metadata, StringBuilder sb) {
      sb.append(metadata.toString());
    }

    public Injector<LogStream> getLogStreamInjector() {
      return logStreamInjector;
    }
  }
}