/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.executor;

import org.apache.flink.annotation.Internal;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.ResultKind;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.utils.PrintUtils;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.Preconditions;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定制CustomTableResultImpl
 *
 * @author wenmo
 * @since 2021/6/7 22:06
 */
@Internal
class CustomTableResultImpl implements TableResult {
    private static Logger logger = LoggerFactory.getLogger(CustomTableResultImpl.class);
    public static final TableResult TABLE_RESULT_OK =
            CustomTableResultImpl.builder()
                    .resultKind(ResultKind.SUCCESS)
                    .tableSchema(TableSchema.builder().field("result", DataTypes.STRING()).build())
                    .data(Collections.singletonList(Row.of("OK")))
                    .build();

    private final JobClient jobClient;
    private final TableSchema tableSchema;
    private final ResultKind resultKind;
    private final CloseableRowIteratorWrapper data;
    private final PrintStyle printStyle;

    private CustomTableResultImpl(
            @Nullable JobClient jobClient,
            TableSchema tableSchema,
            ResultKind resultKind,
            CloseableIterator<Row> data,
            PrintStyle printStyle) {
        this.jobClient = jobClient;
        this.tableSchema =
                Preconditions.checkNotNull(tableSchema, "tableSchema should not be null");
        this.resultKind = Preconditions.checkNotNull(resultKind, "resultKind should not be null");
        Preconditions.checkNotNull(data, "data should not be null");
        this.data = new CloseableRowIteratorWrapper(data);
        this.printStyle = Preconditions.checkNotNull(printStyle, "printStyle should not be null");
    }

    public static TableResult buildTableResult(List<TableSchemaField> fields, List<Row> rows) {
        Builder builder = builder().resultKind(ResultKind.SUCCESS);
        if (fields.size() > 0) {
            TableSchema.Builder tableSchemaBuild = TableSchema.builder();
            for (int i = 0; i < fields.size(); i++) {
                tableSchemaBuild.field(fields.get(i).getName(), fields.get(i).getType());
            }
            builder.tableSchema(tableSchemaBuild.build()).data(rows);
        }
        return builder.build();
    }

    @Override
    public Optional<JobClient> getJobClient() {
        return Optional.ofNullable(jobClient);
    }

    @Override
    public void await() throws InterruptedException, ExecutionException {
        try {
            awaitInternal(-1, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // do nothing
        }
    }

    @Override
    public void await(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        awaitInternal(timeout, unit);
    }

    private void awaitInternal(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (jobClient == null) {
            return;
        }

        ExecutorService executor =
                Executors.newFixedThreadPool(1, r -> new Thread(r, "TableResult-await-thread"));
        try {
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(
                            () -> {
                                while (!data.isFirstRowReady()) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        throw new TableException("Thread is interrupted");
                                    }
                                }
                            },
                            executor);

            if (timeout >= 0) {
                future.get(timeout, unit);
            } else {
                future.get();
            }
        } finally {
            executor.shutdown();
        }
    }

    @Override
    public TableSchema getTableSchema() {
        return tableSchema;
    }

    @Override
    public ResultKind getResultKind() {
        return resultKind;
    }

    @Override
    public CloseableIterator<Row> collect() {
        return data;
    }

    @Override
    public void print() {
        Iterator<Row> it = collect();
        if (printStyle instanceof TableauStyle) {
            int maxColumnWidth = ((TableauStyle) printStyle).getMaxColumnWidth();
            String nullColumn = ((TableauStyle) printStyle).getNullColumn();
            boolean deriveColumnWidthByType =
                    ((TableauStyle) printStyle).isDeriveColumnWidthByType();
            boolean printRowKind = ((TableauStyle) printStyle).isPrintRowKind();
            PrintUtils.printAsTableauForm(
                    getTableSchema(),
                    it,
                    new PrintWriter(System.out),
                    maxColumnWidth,
                    nullColumn,
                    deriveColumnWidthByType,
                    printRowKind);
        } else if (printStyle instanceof RawContentStyle) {
            while (it.hasNext()) {
                logger.info(String.join(",", PrintUtils.rowToString(it.next())));
            }
        } else {
            throw new TableException("Unsupported print style: " + printStyle);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for creating a {@link CustomTableResultImpl}. */
    public static class Builder {
        private JobClient jobClient = null;
        private TableSchema tableSchema = null;
        private ResultKind resultKind = null;
        private CloseableIterator<Row> data = null;
        private PrintStyle printStyle =
                PrintStyle.tableau(Integer.MAX_VALUE, PrintUtils.NULL_COLUMN, false, false);

        private Builder() {}

        /**
         * Specifies job client which associates the submitted Flink job.
         *
         * @param jobClient a {@link JobClient} for the submitted Flink job.
         */
        public Builder jobClient(JobClient jobClient) {
            this.jobClient = jobClient;
            return this;
        }

        /**
         * Specifies table schema of the execution result.
         *
         * @param tableSchema a {@link TableSchema} for the execution result.
         */
        public Builder tableSchema(TableSchema tableSchema) {
            Preconditions.checkNotNull(tableSchema, "tableSchema should not be null");
            this.tableSchema = tableSchema;
            return this;
        }

        /**
         * Specifies result kind of the execution result.
         *
         * @param resultKind a {@link ResultKind} for the execution result.
         */
        public Builder resultKind(ResultKind resultKind) {
            Preconditions.checkNotNull(resultKind, "resultKind should not be null");
            this.resultKind = resultKind;
            return this;
        }

        /**
         * Specifies an row iterator as the execution result.
         *
         * @param rowIterator a row iterator as the execution result.
         */
        public Builder data(CloseableIterator<Row> rowIterator) {
            Preconditions.checkNotNull(rowIterator, "rowIterator should not be null");
            this.data = rowIterator;
            return this;
        }

        /**
         * Specifies an row list as the execution result.
         *
         * @param rowList a row list as the execution result.
         */
        public Builder data(List<Row> rowList) {
            Preconditions.checkNotNull(rowList, "listRows should not be null");
            this.data = CloseableIterator.adapterForIterator(rowList.iterator());
            return this;
        }

        /** Specifies print style. Default is {@link TableauStyle} with max integer column width. */
        public Builder setPrintStyle(PrintStyle printStyle) {
            Preconditions.checkNotNull(printStyle, "printStyle should not be null");
            this.printStyle = printStyle;
            return this;
        }

        /** Returns a {@link TableResult} instance. */
        public TableResult build() {
            return new CustomTableResultImpl(jobClient, tableSchema, resultKind, data, printStyle);
        }
    }

    /** Root interface for all print styles. */
    public interface PrintStyle {
        /**
         * Create a tableau print style with given max column width, null column, change mode
         * indicator and a flag to indicate whether the column width is derived from type (true) or
         * content (false), which prints the result schema and content as tableau form.
         */
        static PrintStyle tableau(
                int maxColumnWidth,
                String nullColumn,
                boolean deriveColumnWidthByType,
                boolean printRowKind) {
            Preconditions.checkArgument(
                    maxColumnWidth > 0, "maxColumnWidth should be greater than 0");
            Preconditions.checkNotNull(nullColumn, "nullColumn should not be null");
            return new TableauStyle(
                    maxColumnWidth, nullColumn, deriveColumnWidthByType, printRowKind);
        }

        /**
         * Create a raw content print style, which only print the result content as raw form. column
         * delimiter is ",", row delimiter is "\n".
         */
        static PrintStyle rawContent() {
            return new RawContentStyle();
        }
    }

    /** print the result schema and content as tableau form. */
    private static final class TableauStyle implements PrintStyle {
        /**
         * A flag to indicate whether the column width is derived from type (true) or content
         * (false).
         */
        private final boolean deriveColumnWidthByType;

        private final int maxColumnWidth;
        private final String nullColumn;
        /** A flag to indicate whether print row kind info. */
        private final boolean printRowKind;

        private TableauStyle(
                int maxColumnWidth,
                String nullColumn,
                boolean deriveColumnWidthByType,
                boolean printRowKind) {
            this.deriveColumnWidthByType = deriveColumnWidthByType;
            this.maxColumnWidth = maxColumnWidth;
            this.nullColumn = nullColumn;
            this.printRowKind = printRowKind;
        }

        public boolean isDeriveColumnWidthByType() {
            return deriveColumnWidthByType;
        }

        int getMaxColumnWidth() {
            return maxColumnWidth;
        }

        String getNullColumn() {
            return nullColumn;
        }

        public boolean isPrintRowKind() {
            return printRowKind;
        }
    }

    /**
     * only print the result content as raw form. column delimiter is ",", row delimiter is "\n".
     */
    private static final class RawContentStyle implements PrintStyle {}

    /**
     * A {@link CloseableIterator} wrapper class that can return whether the first row is ready.
     *
     * <p>The first row is ready when {@link #hasNext} method returns true or {@link #next()} method
     * returns a row. The execution order of {@link TableResult#collect} method and {@link
     * TableResult#await()} may be arbitrary, this class will record whether the first row is ready
     * (or accessed).
     */
    private static final class CloseableRowIteratorWrapper implements CloseableIterator<Row> {
        private final CloseableIterator<Row> iterator;
        private boolean isFirstRowReady = false;

        private CloseableRowIteratorWrapper(CloseableIterator<Row> iterator) {
            this.iterator = iterator;
        }

        @Override
        public void close() throws Exception {
            iterator.close();
        }

        @Override
        public boolean hasNext() {
            boolean hasNext = iterator.hasNext();
            isFirstRowReady = isFirstRowReady || hasNext;
            return hasNext;
        }

        @Override
        public Row next() {
            Row next = iterator.next();
            isFirstRowReady = true;
            return next;
        }

        public boolean isFirstRowReady() {
            return isFirstRowReady || hasNext();
        }
    }
}
