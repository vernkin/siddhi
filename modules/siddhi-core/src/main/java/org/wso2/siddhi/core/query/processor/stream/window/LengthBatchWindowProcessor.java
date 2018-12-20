/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.siddhi.core.query.processor.stream.window;

import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.ComplexEvent;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.event.stream.holder.SnapshotableStreamEventQueue;
import org.wso2.siddhi.core.executor.ConstantExpressionExecutor;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.processor.Processor;
import org.wso2.siddhi.core.table.Table;
import org.wso2.siddhi.core.util.collection.operator.CompiledCondition;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import org.wso2.siddhi.core.util.collection.operator.Operator;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.parser.OperatorParser;
import org.wso2.siddhi.core.util.snapshot.state.SnapshotStateList;
import org.wso2.siddhi.query.api.exception.SiddhiAppValidationException;
import org.wso2.siddhi.query.api.expression.Expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link WindowProcessor} which represent a Batch Window operating based on pre-defined length.
 */
@Extension(
        name = "lengthBatch",
        namespace = "",
        description = "A batch (tumbling) length window that holds a number of events specified as the windowLength. " +
                "The window is updated each time a batch of events that equals the number " +
                "specified as the windowLength arrives.",
        parameters = {
                @Parameter(name = "window.length",
                        description = "The number of events the window should tumble.",
                        type = {DataType.INT}),
        },
        examples = {
                @Example(
                        syntax = "define window cseEventWindow (symbol string, price float, volume int) " +
                                "lengthBatch(10) output all events;\n\n" +
                                "@info(name = 'query0')\n" +
                                "from cseEventStream\n" +
                                "insert into cseEventWindow;\n\n" +
                                "@info(name = 'query1')\n" +
                                "from cseEventWindow\n" +
                                "select symbol, sum(price) as price\n" +
                                "insert all events into outputStream ;",
                        description = "This will processing 10 events as a batch and out put all events."
                )
        }
)
public class LengthBatchWindowProcessor extends WindowProcessor implements FindableProcessor {

    private int length;
    private int count = 0;
    private SnapshotableStreamEventQueue currentEventQueue;
    private SnapshotableStreamEventQueue expiredEventQueue = null;
    private boolean outputExpectsExpiredEvents;
    private SiddhiAppContext siddhiAppContext;
    private StreamEvent resetEvent = null;


    @Override
    protected void init(ExpressionExecutor[] attributeExpressionExecutors, ConfigReader configReader,
                        boolean outputExpectsExpiredEvents, SiddhiAppContext siddhiAppContext) {
        this.outputExpectsExpiredEvents = outputExpectsExpiredEvents;
        this.siddhiAppContext = siddhiAppContext;
        currentEventQueue = new SnapshotableStreamEventQueue(streamEventClonerHolder);
        if (outputExpectsExpiredEvents) {
            expiredEventQueue = new SnapshotableStreamEventQueue(streamEventClonerHolder);
        }
        if (attributeExpressionExecutors.length == 1) {
            length = (Integer) (((ConstantExpressionExecutor) attributeExpressionExecutors[0]).getValue());
        } else {
            throw new SiddhiAppValidationException("Length batch window should only have one parameter (<int> " +
                    "windowLength), but found " + attributeExpressionExecutors.length + " input attributes");
        }
    }

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor,
                           StreamEventCloner streamEventCloner) {
        List<ComplexEventChunk<StreamEvent>> streamEventChunks = new ArrayList<ComplexEventChunk<StreamEvent>>();
        synchronized (this) {
            ComplexEventChunk<StreamEvent> outputStreamEventChunk = new ComplexEventChunk<StreamEvent>(true);
            long currentTime = siddhiAppContext.getTimestampGenerator().currentTime();
            while (streamEventChunk.hasNext()) {
                StreamEvent streamEvent = streamEventChunk.next();
                StreamEvent clonedStreamEvent = streamEventCloner.copyStreamEvent(streamEvent);
                currentEventQueue.add(clonedStreamEvent);
                count++;
                if (count == length) {
                    if (outputExpectsExpiredEvents) {
                        if (expiredEventQueue.getFirst() != null) {
                            while (expiredEventQueue.hasNext()) {
                                StreamEvent expiredEvent = expiredEventQueue.next();
                                expiredEvent.setTimestamp(currentTime);
                            }
                            outputStreamEventChunk.add(expiredEventQueue.getFirst());
                        }
                    }
                    if (expiredEventQueue != null) {
                        expiredEventQueue.clear();
                    }

                    if (currentEventQueue.getFirst() != null) {

                        // add reset event in front of current events
                        outputStreamEventChunk.add(resetEvent);
                        resetEvent = null;

                        if (expiredEventQueue != null) {
                            currentEventQueue.reset();
                            while (currentEventQueue.hasNext()) {
                                StreamEvent currentEvent = currentEventQueue.next();
                                StreamEvent toExpireEvent = streamEventCloner.copyStreamEvent(currentEvent);
                                toExpireEvent.setType(StreamEvent.Type.EXPIRED);
                                expiredEventQueue.add(toExpireEvent);
                            }
                        }

                        resetEvent = streamEventCloner.copyStreamEvent(currentEventQueue.getFirst());
                        resetEvent.setType(ComplexEvent.Type.RESET);
                        outputStreamEventChunk.add(currentEventQueue.getFirst());
                    }
                    currentEventQueue.clear();
                    count = 0;
                    if (outputStreamEventChunk.getFirst() != null) {
                        streamEventChunks.add(outputStreamEventChunk);
                    }
                }
            }
        }
        for (ComplexEventChunk<StreamEvent> outputStreamEventChunk : streamEventChunks) {
            nextProcessor.process(outputStreamEventChunk);
        }
    }

    @Override
    public void start() {
        //Do nothing
    }

    @Override
    public void stop() {
        //Do nothing
    }

    @Override
    public Map<String, Object> currentState() {
        Map<String, Object> state = new HashMap<>();
        synchronized (this) {
            state.put("Count", count);
            state.put("CurrentEventQueue", currentEventQueue.getSnapshot());
            state.put("ExpiredEventQueue", expiredEventQueue.getSnapshot());
            state.put("ResetEvent", resetEvent);
        }
        return state;
    }


    @Override
    public synchronized void restoreState(Map<String, Object> state) {
        count = (int) state.get("Count");
        currentEventQueue.clear();
        currentEventQueue.restore((SnapshotStateList) state.get("CurrentEventQueue"));

        if (expiredEventQueue != null) {
            expiredEventQueue.clear();
            expiredEventQueue.restore((SnapshotStateList) state.get("ExpiredEventQueue"));
        }
        resetEvent = (StreamEvent) state.get("ResetEvent");
    }

    @Override
    public synchronized StreamEvent find(StateEvent matchingEvent, CompiledCondition compiledCondition) {
        return ((Operator) compiledCondition).find(matchingEvent, expiredEventQueue, streamEventCloner);
    }

    @Override
    public CompiledCondition compileCondition(Expression condition, MatchingMetaInfoHolder matchingMetaInfoHolder,
                                              SiddhiAppContext siddhiAppContext,
                                              List<VariableExpressionExecutor> variableExpressionExecutors,
                                              Map<String, Table> tableMap, String queryName) {
        if (expiredEventQueue == null) {
            expiredEventQueue = new SnapshotableStreamEventQueue(streamEventClonerHolder);
        }
        return OperatorParser.constructOperator(expiredEventQueue, condition, matchingMetaInfoHolder,
                siddhiAppContext, variableExpressionExecutors, tableMap, this.queryName);
    }
}
