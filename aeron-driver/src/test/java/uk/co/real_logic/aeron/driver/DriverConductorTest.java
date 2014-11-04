/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.driver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.aeron.common.TimerWheel;
import uk.co.real_logic.aeron.common.command.*;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.agrona.concurrent.CountersManager;
import uk.co.real_logic.agrona.concurrent.OneToOneConcurrentArrayQueue;
import uk.co.real_logic.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import uk.co.real_logic.agrona.concurrent.ringbuffer.RingBuffer;
import uk.co.real_logic.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import uk.co.real_logic.aeron.common.event.EventConfiguration;
import uk.co.real_logic.aeron.common.event.EventLogger;
import uk.co.real_logic.aeron.driver.buffer.TermBuffersFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.common.ErrorCode.INVALID_CHANNEL;
import static uk.co.real_logic.aeron.common.ErrorCode.UNKNOWN_PUBLICATION;
import static uk.co.real_logic.aeron.common.command.ControlProtocolEvents.ADD_PUBLICATION;
import static uk.co.real_logic.aeron.common.command.ControlProtocolEvents.REMOVE_PUBLICATION;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.LogBufferDescriptor.STATE_BUFFER_LENGTH;
import static uk.co.real_logic.aeron.driver.Configuration.*;
import static uk.co.real_logic.aeron.driver.ThreadingMode.DEDICATED;

public class DriverConductorTest
{
    private static final String CHANNEL_URI = "udp://localhost:";
    private static final String INVALID_URI = "udp://";
    private static final int STREAM_ID_1 = 10;
    private static final int STREAM_ID_2 = 20;
    private static final int STREAM_ID_3 = 30;
    private static final int TERM_BUFFER_SZ = Configuration.TERM_BUFFER_SZ_DEFAULT;
    private static final long CORRELATION_ID_1 = 1429;
    private static final long CORRELATION_ID_2 = 1430;
    private static final long CORRELATION_ID_3 = 1431;
    private static final long CORRELATION_ID_4 = 1432;
    private static final long CLIENT_ID = 1433;
    public static final int BUFFER_SIZE = 1024 * 1024;

    private final ByteBuffer toDriverBuffer = ByteBuffer.allocate(
        Configuration.COMMAND_BUFFER_SZ + RingBufferDescriptor.TRAILER_LENGTH);

    private final ByteBuffer toEventBuffer = ByteBuffer.allocate(
        EventConfiguration.BUFFER_SIZE_DEFAULT + RingBufferDescriptor.TRAILER_LENGTH);

    private final TransportPoller transportPoller = mock(TransportPoller.class);
    private final TermBuffersFactory mockTermBuffersFactory = mock(TermBuffersFactory.class);

    private final RingBuffer fromClientCommands = new ManyToOneRingBuffer(new UnsafeBuffer(toDriverBuffer));
    private final RingBuffer toEventReader = new ManyToOneRingBuffer(new UnsafeBuffer(toEventBuffer));
    private final ClientProxy mockClientProxy = mock(ClientProxy.class);

    private final PublicationMessageFlyweight publicationMessage = new PublicationMessageFlyweight();
    private final SubscriptionMessageFlyweight subscriptionMessage = new SubscriptionMessageFlyweight();
    private final RemoveMessageFlyweight removeMessage = new RemoveMessageFlyweight();
    private final CorrelatedMessageFlyweight correlatedMessage = new CorrelatedMessageFlyweight();
    private final UnsafeBuffer writeBuffer = new UnsafeBuffer(ByteBuffer.allocate(256));

    private final EventLogger mockConductorLogger = mock(EventLogger.class);

    private final SenderProxy senderProxy = mock(SenderProxy.class);
    private final ReceiverProxy receiverProxy = mock(ReceiverProxy.class);

    private long currentTime;
    private final TimerWheel wheel = new TimerWheel(
        () -> currentTime, CONDUCTOR_TICK_DURATION_US, TimeUnit.MICROSECONDS, CONDUCTOR_TICKS_PER_WHEEL);

    private DriverConductor driverConductor;

    private final Answer<Void> closeChannelEndpointAnswer =
        (invocation) ->
        {
            final Object args[] = invocation.getArguments();
            final ReceiveChannelEndpoint channelEndpoint = (ReceiveChannelEndpoint)args[0];
            channelEndpoint.close();

            return null;
        };

    @Before
    public void setUp() throws Exception
    {
        when(mockTermBuffersFactory.newPublication(anyObject(), anyInt(), anyInt(), anyInt()))
            .thenReturn(BufferAndFrameHelper.newTestTermBuffers(TERM_BUFFER_SZ, STATE_BUFFER_LENGTH));

        currentTime = 0;

        final UnsafeBuffer counterBuffer = new UnsafeBuffer(new byte[BUFFER_SIZE]);
        final CountersManager countersManager = new CountersManager(new UnsafeBuffer(new byte[BUFFER_SIZE]), counterBuffer);

        final MediaDriver.Context ctx = new MediaDriver.Context()
            .receiverNioSelector(transportPoller)
            .conductorNioSelector(transportPoller)
            .unicastSenderFlowControl(UnicastSenderFlowControl::new)
            .multicastSenderFlowControl(MaxMulticastSenderFlowControl::new)
            .conductorTimerWheel(wheel)
            // TODO: remove
            .conductorCommandQueue(new OneToOneConcurrentArrayQueue<>(1024))
            .eventLogger(mockConductorLogger)
            .termBuffersFactory(mockTermBuffersFactory)
            .countersManager(countersManager);

        ctx.toEventReader(toEventReader);
        ctx.toDriverCommands(fromClientCommands);
        ctx.clientProxy(mockClientProxy);
        ctx.countersBuffer(counterBuffer);

        final SystemCounters mockSystemCounters = mock(SystemCounters.class);
        ctx.systemCounters(mockSystemCounters);
        when(mockSystemCounters.bytesReceived()).thenReturn(mock(AtomicCounter.class));

        ctx.receiverProxy(receiverProxy);
        ctx.senderProxy(senderProxy);
        ctx.driverConductorProxy(new DriverConductorProxy(DEDICATED, ctx.conductorCommandQueue(), mock(AtomicCounter.class)));

        driverConductor = new DriverConductor(ctx);

        doAnswer(closeChannelEndpointAnswer).when(receiverProxy).closeReceiveChannelEndpoint(any());
    }

    @After
    public void tearDown() throws Exception
    {
        driverConductor.onClose();
    }

    @Test
    public void shouldBeAbleToAddSinglePublication() throws Exception
    {
        writePublicationMessage(ADD_PUBLICATION, 1, 2, 4000, CORRELATION_ID_1);

        driverConductor.doWork();

        verifySenderNotifiedOfNewPublication();

        verify(mockClientProxy).onPublicationReady(
            eq(CHANNEL_URI + 4000), eq(2), eq(1), anyInt(),
            any(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void shouldBeAbleToAddSingleSubscription() throws Exception
    {
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);

        driverConductor.doWork();

        verify(mockClientProxy).operationSucceeded(CORRELATION_ID_1);

        assertNotNull(driverConductor.receiverChannelEndpoint(UdpChannel.parse(CHANNEL_URI + 4000)));
    }

    @Test
    public void shouldBeAbleToAddAndRemoveSingleSubscription() throws Exception
    {
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);
        writeSubscriptionMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);

        driverConductor.doWork();

        assertNull(driverConductor.receiverChannelEndpoint(UdpChannel.parse(CHANNEL_URI + 4000)));
    }

    @Test
    public void shouldBeAbleToAddMultipleStreams() throws Exception
    {
        writePublicationMessage(ADD_PUBLICATION, 1, 2, 4001, CORRELATION_ID_1);
        writePublicationMessage(ADD_PUBLICATION, 1, 3, 4002, CORRELATION_ID_2);
        writePublicationMessage(ADD_PUBLICATION, 3, 2, 4003, CORRELATION_ID_3);
        writePublicationMessage(ADD_PUBLICATION, 3, 4, 4004, CORRELATION_ID_4);

        driverConductor.doWork();

        verify(senderProxy, times(4)).newPublication(any());
    }

    @Test
    public void shouldBeAbleToRemoveSingleStream() throws Exception
    {
        writePublicationMessage(ADD_PUBLICATION, 1, 2, 4005, CORRELATION_ID_1);
        writePublicationMessage(REMOVE_PUBLICATION, 1, 2, 4005, CORRELATION_ID_1);

        driverConductor.doWork();

        processTimersUntil(() -> wheel.clock().time() >= CLIENT_LIVENESS_TIMEOUT_NS + PUBLICATION_LINGER_NS * 2);

        verify(senderProxy).closePublication(any());
        assertNull(driverConductor.senderChannelEndpoint(UdpChannel.parse(CHANNEL_URI + 4005)));
    }

    @Test
    public void shouldBeAbleToRemoveMultipleStreams() throws Exception
    {
        writePublicationMessage(ADD_PUBLICATION, 1, 2, 4006, CORRELATION_ID_1);
        writePublicationMessage(ADD_PUBLICATION, 1, 3, 4007, CORRELATION_ID_2);
        writePublicationMessage(ADD_PUBLICATION, 3, 2, 4008, CORRELATION_ID_3);
        writePublicationMessage(ADD_PUBLICATION, 3, 4, 4008, CORRELATION_ID_4);

        removePublicationMessage(CORRELATION_ID_1);
        removePublicationMessage(CORRELATION_ID_2);
        removePublicationMessage(CORRELATION_ID_3);
        removePublicationMessage(CORRELATION_ID_4);

        driverConductor.doWork();

        processTimersUntil(() -> wheel.clock().time() >= PUBLICATION_LINGER_NS * 2 + CLIENT_LIVENESS_TIMEOUT_NS * 2);

        verify(senderProxy, times(4)).closePublication(any());
    }

    // TODO: check publication refs from 0 to 1

    private void removePublicationMessage(final long registrationId)
    {
        removeMessage.wrap(writeBuffer, 0);
        removeMessage.registrationId(registrationId);
        assertTrue(fromClientCommands.write(REMOVE_PUBLICATION, writeBuffer, 0, RemoveMessageFlyweight.length()));
    }

    @Test
    public void shouldKeepSubscriptionMediaEndpointUponRemovalOfAllButOneSubscriber() throws Exception
    {
        final UdpChannel udpChannel = UdpChannel.parse(CHANNEL_URI + 4000);

        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_2, CORRELATION_ID_2);
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_3, CORRELATION_ID_3);

        driverConductor.doWork();

        final ReceiveChannelEndpoint channelEndpoint = driverConductor.receiverChannelEndpoint(udpChannel);

        assertNotNull(channelEndpoint);
        assertThat(channelEndpoint.streamCount(), is(3));

        writeSubscriptionMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);
        writeSubscriptionMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_2, CORRELATION_ID_2);

        driverConductor.doWork();

        assertNotNull(driverConductor.receiverChannelEndpoint(udpChannel));
        assertThat(channelEndpoint.streamCount(), is(1));
    }

    @Test
    public void shouldOnlyRemoveSubscriptionMediaEndpointUponRemovalOfAllSubscribers() throws Exception
    {
        final UdpChannel udpChannel = UdpChannel.parse(CHANNEL_URI + 4000);

        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_2, CORRELATION_ID_2);
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_3, CORRELATION_ID_3);

        driverConductor.doWork();

        final ReceiveChannelEndpoint channelEndpoint = driverConductor.receiverChannelEndpoint(udpChannel);

        assertNotNull(channelEndpoint);
        assertThat(channelEndpoint.streamCount(), is(3));

        writeSubscriptionMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_2, CORRELATION_ID_2);
        writeSubscriptionMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_3, CORRELATION_ID_3);

        driverConductor.doWork();

        assertNotNull(driverConductor.receiverChannelEndpoint(udpChannel));
        assertThat(channelEndpoint.streamCount(), is(1));

        writeSubscriptionMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);

        driverConductor.doWork();

        assertNull(driverConductor.receiverChannelEndpoint(udpChannel));
    }

    @Test
    public void shouldErrorOnRemoveChannelOnUnknownSessionId() throws Exception
    {
        writePublicationMessage(ADD_PUBLICATION, 1, 2, 4000, CORRELATION_ID_1);
        writePublicationMessage(REMOVE_PUBLICATION, 2, 2, 4000, CORRELATION_ID_1);

        driverConductor.doWork();

        verifySenderNotifiedOfNewPublication();

        verify(mockClientProxy).onError(eq(UNKNOWN_PUBLICATION), argThat(not(isEmptyOrNullString())), any(), anyInt());
        verifyNeverSucceeds();
        verifyExceptionLogged();
    }

    @Test
    public void shouldErrorOnRemoveChannelOnUnknownStreamId() throws Exception
    {
        writePublicationMessage(ADD_PUBLICATION, 1, 2, 4000, CORRELATION_ID_1);
        writePublicationMessage(REMOVE_PUBLICATION, 1, 3, 4000, CORRELATION_ID_1);

        driverConductor.doWork();

        verifyPublicationClosed(never());
        verify(mockClientProxy).onError(eq(UNKNOWN_PUBLICATION), argThat(not(isEmptyOrNullString())), any(), anyInt());
        verifyNeverSucceeds();
        verifyExceptionLogged();
    }

    @Test
    public void shouldErrorOnAddSubscriptionWithInvalidUri() throws Exception
    {
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, INVALID_URI, STREAM_ID_1, CORRELATION_ID_1);

        driverConductor.doWork();
        driverConductor.doWork();

        verify(senderProxy, never()).newPublication(any());

        verify(mockClientProxy).onError(eq(INVALID_CHANNEL), argThat(not(isEmptyOrNullString())), any(), anyInt());
        verifyNeverSucceeds();
        verifyExceptionLogged();
    }

    @Test
    public void shouldTimeoutPublication() throws Exception
    {
        writePublicationMessage(ADD_PUBLICATION, 1, 2, 4000, CORRELATION_ID_1);

        driverConductor.doWork();

        verifySenderNotifiedOfNewPublication();

        processTimersUntil(() -> wheel.clock().time() >= Configuration.PUBLICATION_LINGER_NS + CLIENT_LIVENESS_TIMEOUT_NS * 2);

        verifyPublicationClosed(times(1));
        assertNull(driverConductor.senderChannelEndpoint(UdpChannel.parse(CHANNEL_URI + 4000)));
    }

    @Test
    public void shouldNotTimeoutPublicationOnKeepAlive() throws Exception
    {
        writePublicationMessage(ADD_PUBLICATION, 1, 2, 4000, CORRELATION_ID_1);

        driverConductor.doWork();

        verifySenderNotifiedOfNewPublication();

        processTimersUntil(() -> wheel.clock().time() >= CLIENT_LIVENESS_TIMEOUT_NS / 2);

        writeKeepaliveClientMessage();

        processTimersUntil(() -> wheel.clock().time() >= CLIENT_LIVENESS_TIMEOUT_NS + 1000);

        writeKeepaliveClientMessage();

        processTimersUntil(() -> wheel.clock().time() >= CLIENT_LIVENESS_TIMEOUT_NS * 2);

        verifyPublicationClosed(never());
    }

    @Test
    public void shouldTimeoutSubscription() throws Exception
    {
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);

        driverConductor.doWork();

        verifyReceiverSubscribes();
        assertNotNull(driverConductor.receiverChannelEndpoint(UdpChannel.parse(CHANNEL_URI + 4000)));

        processTimersUntil(() -> wheel.clock().time() >= CLIENT_LIVENESS_TIMEOUT_NS * 2);

        verifyReceiverRemovesSubscription(times(1));
        assertNull(driverConductor.receiverChannelEndpoint(UdpChannel.parse(CHANNEL_URI + 4000)));
    }

    @Test
    public void shouldNotTimeoutSubscriptionOnKeepAlive() throws Exception
    {
        writeSubscriptionMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, CHANNEL_URI + 4000, STREAM_ID_1, CORRELATION_ID_1);

        driverConductor.doWork();

        assertNotNull(driverConductor.receiverChannelEndpoint(UdpChannel.parse(CHANNEL_URI + 4000)));
        verifyReceiverSubscribes();

        processTimersUntil(() -> wheel.clock().time() >= CLIENT_LIVENESS_TIMEOUT_NS / 1);

        writeKeepaliveClientMessage();

        processTimersUntil(() -> wheel.clock().time() >= CLIENT_LIVENESS_TIMEOUT_NS + 1000);

        writeKeepaliveClientMessage();

        processTimersUntil(() -> wheel.clock().time() >= CLIENT_LIVENESS_TIMEOUT_NS * 2);

        verifyReceiverRemovesSubscription(never());
        assertNotNull(driverConductor.receiverChannelEndpoint(UdpChannel.parse(CHANNEL_URI + 4000)));
    }

    private void verifyReceiverRemovesSubscription(final VerificationMode times)
    {
        verify(receiverProxy, times).removeSubscription(any(), anyInt());
    }

    private void verifyReceiverSubscribes()
    {
        verify(receiverProxy).addSubscription(any(), eq(STREAM_ID_1));
    }

    private void verifyPublicationClosed(final VerificationMode times)
    {
        verify(senderProxy, times).closePublication(any());
    }

    private void verifyExceptionLogged()
    {
        verify(mockConductorLogger).logException(any());
    }

    private void verifyNeverSucceeds()
    {
        verify(mockClientProxy, never()).operationSucceeded(anyLong());
    }

    private void writePublicationMessage(
        final int msgTypeId,
        final int sessionId,
        final int streamId,
        final int port,
        final long correlationId)
    {
        publicationMessage.wrap(writeBuffer, 0);
        publicationMessage.streamId(streamId);
        publicationMessage.sessionId(sessionId);
        publicationMessage.channel(CHANNEL_URI + port);
        publicationMessage.clientId(CLIENT_ID);
        publicationMessage.correlationId(correlationId);

        fromClientCommands.write(msgTypeId, writeBuffer, 0, publicationMessage.length());
    }

    private void verifySenderNotifiedOfNewPublication()
    {
        final ArgumentCaptor<DriverPublication> captor = ArgumentCaptor.forClass(DriverPublication.class);
        verify(senderProxy, times(1)).newPublication(captor.capture());

        final DriverPublication publication = captor.getValue();
        assertThat(publication.sessionId(), is(1));
        assertThat(publication.streamId(), is(2));
        assertThat(publication.id(), is(CORRELATION_ID_1));
    }

    private void writeSubscriptionMessage(
        final int msgTypeId, final String channel, final int streamId, final long registrationCorrelationId)
    {
        subscriptionMessage.wrap(writeBuffer, 0);
        subscriptionMessage.streamId(streamId)
                           .channel(channel)
                           .registrationCorrelationId(registrationCorrelationId)
                           .correlationId(registrationCorrelationId)
                           .clientId(CLIENT_ID);

        fromClientCommands.write(msgTypeId, writeBuffer, 0, subscriptionMessage.length());
    }

    private void writeKeepaliveClientMessage()
    {
        correlatedMessage.wrap(writeBuffer, 0);
        correlatedMessage.clientId(CLIENT_ID);
        correlatedMessage.correlationId(0);

        fromClientCommands.write(ControlProtocolEvents.CLIENT_KEEPALIVE, writeBuffer, 0, CorrelatedMessageFlyweight.LENGTH);
    }

    private long processTimersUntil(final BooleanSupplier condition) throws Exception
    {
        final long startTime = wheel.clock().time();

        while (!condition.getAsBoolean())
        {
            if (wheel.calculateDelayInMs() > 0)
            {
                currentTime += TimeUnit.MICROSECONDS.toNanos(Configuration.CONDUCTOR_TICK_DURATION_US);
            }

            driverConductor.doWork();
        }

        return wheel.clock().time() - startTime;
    }
}
