package ch.usi.da.paxos.lab;
/* 
 * Copyright (c) 2013 Università della Svizzera italiana (USI)
 * 
 * This file is part of URingPaxos.
 *
 * URingPaxos is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * URingPaxos is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with URingPaxos.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.apache.log4j.Logger;

import ch.usi.da.paxos.message.Message;
import ch.usi.da.paxos.message.MessageType;
import ch.usi.da.paxos.message.PaxosRole;
import ch.usi.da.paxos.message.Value;
import ch.usi.da.paxos.ring.Node;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpStandardSocketOptions;

/**
 * Name: SCTPListener<br>
 * Description: <br>
 * 
 * Creation date: Aug 15, 2012<br>
 * $Id$
 * 
 * @author Samuel Benz <benz@geoid.ch>
 */
public class SCTPSenderTest implements Runnable {

	private final static Logger logger = Logger.getLogger(SCTPSenderTest.class);
	
	private final SctpChannel client;
	
	private ByteBuffer buffer = ByteBuffer.allocate(16384);
	
	/**
	 * @throws IOException 
	 */
	public SCTPSenderTest() throws IOException{
		InetSocketAddress addr = new InetSocketAddress(Node.getHostAddress(false),2020);
		client = SctpChannel.open(addr,0,0);
		client.configureBlocking(true);
		// System.out.println(client.supportedOptions());
		// [SO_RCVBUF, SCTP_SET_PEER_PRIMARY_ADDR, SCTP_DISABLE_FRAGMENTS, 
		// SO_LINGER, SO_SNDBUF, SCTP_NODELAY, SCTP_PRIMARY_ADDR, SCTP_FRAGMENT_INTERLEAVE,
		// SCTP_EXPLICIT_COMPLETE, SCTP_INIT_MAXSTREAMS]
		client.setOption(SctpStandardSocketOptions.SCTP_NODELAY,true);
		client.setOption(SctpStandardSocketOptions.SCTP_DISABLE_FRAGMENTS,true);
	}
	
	@Override
	public void run() {
		int send = 0;
		try {
			MessageInfo info = MessageInfo.createOutgoing(null,0);
			info.unordered(true);
			while(send < 500000){
				Long t = System.nanoTime();
				Message m = new Message(0,0,PaxosRole.Leader,MessageType.Value,0,new Value(t.toString(),new byte[8912]));
				if(m != null){
					buffer.put(Message.toWire(m));
					buffer.flip();
					client.send(buffer,info);
					buffer.clear();
					send++;
				}
			}
			client.shutdown();
			client.close();
		} catch (IOException e) {
			logger.error(e);
		}	
	}
	
}