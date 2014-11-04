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
package uk.co.real_logic.aeron.common;

import org.junit.Test;
import uk.co.real_logic.aeron.common.command.PublicationReadyFlyweight;
import uk.co.real_logic.aeron.common.command.PublicationMessageFlyweight;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.aeron.common.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.common.protocol.ErrorFlyweight;
import uk.co.real_logic.aeron.common.protocol.HeaderFlyweight;
import uk.co.real_logic.aeron.common.protocol.NakFlyweight;

import java.nio.ByteBuffer;
import java.util.stream.IntStream;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class FlyweightTest
{
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(512);

    private final UnsafeBuffer aBuff = new UnsafeBuffer(buffer);
    private final HeaderFlyweight encodeHeader = new HeaderFlyweight();
    private final HeaderFlyweight decodeHeader = new HeaderFlyweight();
    private final DataHeaderFlyweight encodeDataHeader = new DataHeaderFlyweight();
    private final DataHeaderFlyweight decodeDataHeader = new DataHeaderFlyweight();
    private final PublicationMessageFlyweight encodePublication = new PublicationMessageFlyweight();
    private final PublicationMessageFlyweight decodePublication = new PublicationMessageFlyweight();
    private final ErrorFlyweight encodeError = new ErrorFlyweight();
    private final ErrorFlyweight decodeError = new ErrorFlyweight();
    private final PublicationReadyFlyweight encodeNewBuffer = new PublicationReadyFlyweight();
    private final PublicationReadyFlyweight decodeNewBuffer = new PublicationReadyFlyweight();
    private final NakFlyweight encodeNakHeader = new NakFlyweight();
    private final NakFlyweight decodeNakHeader = new NakFlyweight();

    @Test
    public void shouldWriteCorrectValuesForGenericHeaderFields()
    {
        encodeHeader.wrap(aBuff, 0);

        encodeHeader.version((short)1);
        encodeHeader.flags(DataHeaderFlyweight.BEGIN_AND_END_FLAGS);
        encodeHeader.headerType(HeaderFlyweight.HDR_TYPE_DATA);
        encodeHeader.frameLength(8);

        // little endian
        assertThat(buffer.get(0), is((byte)0x01));
        assertThat(buffer.get(1), is((byte)0xC0));
        assertThat(buffer.get(2), is((byte)HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(buffer.get(3), is((byte)0x00));
        assertThat(buffer.get(4), is((byte)0x08));
        assertThat(buffer.get(5), is((byte)0x00));
        assertThat(buffer.get(6), is((byte)0x00));
        assertThat(buffer.get(7), is((byte)0x00));
    }

    @Test
    public void shouldReadWhatIsWrittenToGenericHeaderFields()
    {
        encodeHeader.wrap(aBuff, 0);

        encodeHeader.version((short)1);
        encodeHeader.flags((short)0);
        encodeHeader.headerType(HeaderFlyweight.HDR_TYPE_DATA);
        encodeHeader.frameLength(8);

        decodeHeader.wrap(aBuff, 0);
        assertThat(decodeHeader.version(), is((short)1));
        assertThat(decodeHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(decodeHeader.frameLength(), is(8));
    }

    @Test
    public void shouldWriteAndReadMultipleFramesCorrectly()
    {
        encodeHeader.wrap(aBuff, 0);

        encodeHeader.version((short)1);
        encodeHeader.flags((short)0);
        encodeHeader.headerType(HeaderFlyweight.HDR_TYPE_DATA);
        encodeHeader.frameLength(8);

        encodeHeader.wrap(aBuff, 8);
        encodeHeader.version((short)2);
        encodeHeader.flags((short)0x01);
        encodeHeader.headerType(HeaderFlyweight.HDR_TYPE_SM);
        encodeHeader.frameLength(8);

        decodeHeader.wrap(aBuff, 0);
        assertThat(decodeHeader.version(), is((short)1));
        assertThat(decodeHeader.flags(), is((short)0));
        assertThat(decodeHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(decodeHeader.frameLength(), is(8));

        decodeHeader.wrap(aBuff, 8);
        assertThat(decodeHeader.version(), is((short)2));
        assertThat(decodeHeader.flags(), is((short)0x01));
        assertThat(decodeHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_SM));
        assertThat(decodeHeader.frameLength(), is(8));
    }

    @Test
    public void shouldReadAndWriteDataHeaderCorrectly()
    {
        encodeDataHeader.wrap(aBuff, 0);

        encodeDataHeader.version((short)1);
        encodeDataHeader.flags(DataHeaderFlyweight.BEGIN_AND_END_FLAGS);
        encodeDataHeader.headerType(HeaderFlyweight.HDR_TYPE_DATA);
        encodeDataHeader.frameLength(DataHeaderFlyweight.HEADER_LENGTH);
        encodeDataHeader.sessionId(0xdeadbeef);
        encodeDataHeader.streamId(0x44332211);
        encodeDataHeader.termId(0x99887766);

        decodeDataHeader.wrap(aBuff, 0);
        assertThat(decodeDataHeader.version(), is((short)1));
        assertThat(decodeDataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(decodeDataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(decodeDataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(decodeDataHeader.sessionId(), is(0xdeadbeef));
        assertThat(decodeDataHeader.streamId(), is(0x44332211));
        assertThat(decodeDataHeader.termId(), is(0x99887766));
        assertThat(decodeDataHeader.dataOffset(), is(DataHeaderFlyweight.HEADER_LENGTH));
    }

    @Test
    public void shouldEncodeAndDecodeNakCorrectly()
    {
        encodeNakHeader.wrap(aBuff, 0);
        encodeNakHeader.version((short)1);
        encodeNakHeader.flags((byte)0);
        encodeNakHeader.headerType(HeaderFlyweight.HDR_TYPE_NAK);
        encodeNakHeader.frameLength(NakFlyweight.HEADER_LENGTH);
        encodeNakHeader.sessionId(0xdeadbeef);
        encodeNakHeader.streamId(0x44332211);
        encodeNakHeader.termId(0x99887766);
        encodeNakHeader.termOffset(0x22334);
        encodeNakHeader.length(512);

        decodeNakHeader.wrap(aBuff, 0);
        assertThat(decodeNakHeader.version(), is((short)1));
        assertThat(decodeNakHeader.flags(), is((short)0));
        assertThat(decodeNakHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_NAK));
        assertThat(decodeNakHeader.frameLength(), is(NakFlyweight.HEADER_LENGTH));
        assertThat(decodeNakHeader.sessionId(), is(0xdeadbeef));
        assertThat(decodeNakHeader.streamId(), is(0x44332211));
        assertThat(decodeNakHeader.termId(), is(0x99887766));
        assertThat(decodeNakHeader.termOffset(), is(0x22334));
        assertThat(decodeNakHeader.length(), is(512));
    }

    @Test
    public void shouldEncodeAndDecodeStringsCorrectly()
    {
        encodePublication.wrap(aBuff, 0);

        final String example = "abcç̀漢字仮名交じり文";
        encodePublication.channel(example);

        decodePublication.wrap(aBuff, 0);

        assertThat(decodePublication.channel(), is(example));
    }

    @Test
    public void shouldReadAndWriteErrorHeaderWithoutErrorStringCorrectly()
    {
        final ByteBuffer originalBuffer = ByteBuffer.allocateDirect(256);
        final UnsafeBuffer originalUnsafeBuffer = new UnsafeBuffer(originalBuffer);

        encodeDataHeader.wrap(originalUnsafeBuffer, 0);
        encodeDataHeader.version((short)1);
        encodeDataHeader.flags(DataHeaderFlyweight.BEGIN_AND_END_FLAGS);
        encodeDataHeader.headerType(HeaderFlyweight.HDR_TYPE_DATA);
        encodeDataHeader.frameLength(DataHeaderFlyweight.HEADER_LENGTH);
        encodeDataHeader.sessionId(0xdeadbeef);
        encodeDataHeader.streamId(0x44332211);
        encodeDataHeader.termId(0x99887766);

        encodeError.wrap(aBuff, 0);
        encodeError.version((short)1);
        encodeError.flags((short)0);
        encodeError.headerType(HeaderFlyweight.HDR_TYPE_ERR);
        encodeError.frameLength(encodeDataHeader.frameLength() + 12);
        encodeError.offendingHeader(encodeDataHeader, encodeDataHeader.frameLength());

        decodeError.wrap(aBuff, 0);
        assertThat(decodeError.version(), is((short)1));
        assertThat(decodeError.flags(), is((short)0));
        assertThat(decodeError.headerType(), is(HeaderFlyweight.HDR_TYPE_ERR));
        assertThat(decodeError.frameLength(), is(encodeDataHeader.frameLength() + ErrorFlyweight.HEADER_LENGTH));

        decodeDataHeader.wrap(aBuff, decodeError.offendingHeaderOffset());
        assertThat(decodeDataHeader.version(), is((short)1));
        assertThat(decodeDataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(decodeDataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(decodeDataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(decodeDataHeader.sessionId(), is(0xdeadbeef));
        assertThat(decodeDataHeader.streamId(), is(0x44332211));
        assertThat(decodeDataHeader.termId(), is(0x99887766));
        assertThat(decodeDataHeader.dataOffset(), is(encodeDataHeader.frameLength() + ErrorFlyweight.HEADER_LENGTH));
    }

    @Test
    public void shouldReadAndWriteErrorHeaderWithErrorStringCorrectly()
    {
        final String errorString = "this is an error";
        final ByteBuffer originalBuffer = ByteBuffer.allocateDirect(256);
        final UnsafeBuffer originalUnsafeBuffer = new UnsafeBuffer(originalBuffer);

        encodeDataHeader.wrap(originalUnsafeBuffer, 0);
        encodeDataHeader.version((short)1);
        encodeDataHeader.flags(DataHeaderFlyweight.BEGIN_AND_END_FLAGS);
        encodeDataHeader.headerType(HeaderFlyweight.HDR_TYPE_DATA);
        encodeDataHeader.frameLength(DataHeaderFlyweight.HEADER_LENGTH);
        encodeDataHeader.sessionId(0xdeadbeef);
        encodeDataHeader.streamId(0x44332211);
        encodeDataHeader.termId(0x99887766);

        encodeError.wrap(aBuff, 0);
        encodeError.version((short)1);
        encodeError.flags((short)0);
        encodeError.headerType(HeaderFlyweight.HDR_TYPE_ERR);
        encodeError.frameLength(encodeDataHeader.frameLength() + ErrorFlyweight.HEADER_LENGTH + errorString.length());
        encodeError.offendingHeader(encodeDataHeader, encodeDataHeader.frameLength());
        encodeError.errorMessage(errorString.getBytes());

        decodeError.wrap(aBuff, 0);
        assertThat(decodeError.version(), is((short)1));
        assertThat(decodeError.flags(), is((short)0));
        assertThat(decodeError.headerType(), is(HeaderFlyweight.HDR_TYPE_ERR));
        assertThat(decodeError.frameLength(),
                   is(encodeDataHeader.frameLength() + ErrorFlyweight.HEADER_LENGTH + errorString.length()));

        decodeDataHeader.wrap(aBuff, decodeError.offendingHeaderOffset());
        assertThat(decodeDataHeader.version(), is((short)1));
        assertThat(decodeDataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(decodeDataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(decodeDataHeader.frameLength(), is(encodeDataHeader.frameLength()));
        assertThat(decodeDataHeader.sessionId(), is(0xdeadbeef));
        assertThat(decodeDataHeader.streamId(), is(0x44332211));
        assertThat(decodeDataHeader.termId(), is(0x99887766));
        assertThat(decodeDataHeader.dataOffset(), is(encodeDataHeader.frameLength() + ErrorFlyweight.HEADER_LENGTH));
        assertThat(decodeError.errorMessageOffset(), is(encodeDataHeader.frameLength() + ErrorFlyweight.HEADER_LENGTH));
        assertThat(decodeError.errorStringLength(), is(errorString.length()));
        assertThat(decodeError.errorMessageAsBytes(), is(errorString.getBytes()));
    }

    @Test
    public void newBufferMessagesSupportMultipleVariableLengthFields()
    {
        // given the buffers are clean to begin with
        assertLengthFindsNonZeroedBytes(0);
        encodeNewBuffer.wrap(aBuff, 0);

        encodeNewBuffer.streamId(1)
                       .sessionId(2)
                       .termId(3)
                       .bufferOffset(0, 1)
                       .bufferOffset(1, 2)
                       .bufferOffset(2, 3)
                       .bufferLength(0, 1)
                       .bufferLength(1, 2)
                       .bufferLength(2, 3)
                       .location(0, "def")
                       .location(1, "ghi")
                       .location(2, "jkl")
                       .location(3, "def")
                       .location(4, "ghi")
                       .location(5, "jkl")
                       .channel("abc");

        assertLengthFindsNonZeroedBytes(encodeNewBuffer.length());
        decodeNewBuffer.wrap(aBuff, 0);

        assertThat(decodeNewBuffer.streamId(), is(1));
        assertThat(decodeNewBuffer.sessionId(), is(2));
        assertThat(decodeNewBuffer.termId(), is(3));

        assertThat(decodeNewBuffer.bufferOffset(0), is(1));
        assertThat(decodeNewBuffer.bufferOffset(1), is(2));
        assertThat(decodeNewBuffer.bufferOffset(2), is(3));

        assertThat(decodeNewBuffer.bufferLength(0), is(1));
        assertThat(decodeNewBuffer.bufferLength(1), is(2));
        assertThat(decodeNewBuffer.bufferLength(2), is(3));

        assertThat(decodeNewBuffer.location(0), is("def"));
        assertThat(decodeNewBuffer.location(1), is("ghi"));
        assertThat(decodeNewBuffer.location(2), is("jkl"));

        assertThat(decodeNewBuffer.location(3), is("def"));
        assertThat(decodeNewBuffer.location(4), is("ghi"));
        assertThat(decodeNewBuffer.location(5), is("jkl"));

        assertThat(decodeNewBuffer.channel(), is("abc"));
    }

    private void assertLengthFindsNonZeroedBytes(final int length)
    {
        IntStream.range(aBuff.capacity() - 1, length).forEach(i -> assertThat(aBuff.getByte(i), is((byte)0)));
    }
}
