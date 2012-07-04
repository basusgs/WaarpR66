/**
 * This file is part of Waarp Project.
 * 
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author tags. See the
 * COPYRIGHT.txt in the distribution for a full listing of individual contributors.
 * 
 * All Waarp Project is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Waarp is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Waarp . If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.protocol.localhandler.packet;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolPacketException;

/**
 * End of Transfer class
 * 
 * header = "request" middle = way end = empty
 * 
 * @author frederic bregier
 */
public class EndTransferPacket extends AbstractLocalPacket {
	private static final byte	ASKVALIDATE		= 0;

	private static final byte	ANSWERVALIDATE	= 1;

	private final byte			request;

	private byte				way;

	/**
	 * @param headerLength
	 * @param middleLength
	 * @param endLength
	 * @param buf
	 * @return the new EndTransferPacket from buffer
	 * @throws OpenR66ProtocolPacketException
	 */
	public static EndTransferPacket createFromBuffer(int headerLength,
			int middleLength, int endLength, ChannelBuffer buf)
			throws OpenR66ProtocolPacketException {
		if (headerLength - 1 != 1) {
			throw new OpenR66ProtocolPacketException("Not enough data");
		}
		if (middleLength != 1) {
			throw new OpenR66ProtocolPacketException("Not enough data");
		}
		final byte bheader = buf.readByte();
		byte valid = buf.readByte();
		return new EndTransferPacket(bheader, valid);
	}

	/**
	 * @param request
	 * @param valid
	 */
	private EndTransferPacket(byte request, byte valid) {
		this.request = request;
		way = valid;
	}

	/**
	 * @param request
	 */
	public EndTransferPacket(byte request) {
		this.request = request;
		way = ASKVALIDATE;
	}

	/*
	 * (non-Javadoc)
	 * @see org.waarp.openr66.protocol.localhandler.packet.AbstractLocalPacket#createEnd()
	 */
	@Override
	public void createEnd() {
		end = ChannelBuffers.EMPTY_BUFFER;
	}

	/*
	 * (non-Javadoc)
	 * @see org.waarp.openr66.protocol.localhandler.packet.AbstractLocalPacket#createHeader()
	 */
	@Override
	public void createHeader() {
		byte[] newbytes = {
				request };
		header = ChannelBuffers.wrappedBuffer(newbytes);
	}

	/*
	 * (non-Javadoc)
	 * @see org.waarp.openr66.protocol.localhandler.packet.AbstractLocalPacket#createMiddle()
	 */
	@Override
	public void createMiddle() {
		byte[] newbytes = {
				way };
		middle = ChannelBuffers.wrappedBuffer(newbytes);
	}

	@Override
	public byte getType() {
		return LocalPacketFactory.ENDTRANSFERPACKET;
	}

	/*
	 * (non-Javadoc)
	 * @see org.waarp.openr66.protocol.localhandler.packet.AbstractLocalPacket#toString()
	 */
	@Override
	public String toString() {
		return "EndTransferPacket: " + request + " " + way;
	}

	/**
	 * @return the requestId
	 */
	public byte getRequest() {
		return request;
	}

	/**
	 * @return True if this packet is to be validated
	 */
	public boolean isToValidate() {
		return way == ASKVALIDATE;
	}

	/**
	 * Validate the connection
	 */
	public void validate() {
		way = ANSWERVALIDATE;
		header = null;
		middle = null;
		end = null;
	}
}
