package ch.usi.da.paxos.ring;
/* 
 * Copyright (c) 2015 Università della Svizzera italiana (USI)
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import ch.usi.da.paxos.api.ConfigKey;
import ch.usi.da.paxos.api.Learner;
import ch.usi.da.paxos.api.PaxosNode;
import ch.usi.da.paxos.api.PaxosRole;
import ch.usi.da.paxos.message.Control;
import ch.usi.da.paxos.message.ControlType;
import ch.usi.da.paxos.storage.Decision;

/**
 * Name: ElasticLearnerRole<br>
 * Description: <br>
 * 
 * Creation date: Sept 08, 2015<br>
 * $Id$
 * 
 * @author Samuel Benz benz@geoid.ch
 */
public class ElasticLearnerRole extends Role implements Learner {

	private final static Logger logger = Logger.getLogger(ElasticLearnerRole.class);
	
	private final PaxosNode node;
	
	private final int replication_group;
	
	private final Map<Integer,RingDescription> ringmap = new HashMap<Integer,RingDescription>();
	
	private final List<Integer> rings = new ArrayList<Integer>();
	
	private final int maxRing = 100;
	
	private final BlockingQueue<Decision> values = new LinkedBlockingQueue<Decision>(); 
	
	private final LearnerRole[] learner = new LearnerRole[maxRing];
	
	private int deliverRing;
	
	private int newRing;
	
	private final long[] skip_count = new long[maxRing];

	private final long[] v_count = new long[maxRing];

	private long v_subscribe = 0;
	
	private boolean deliver_skip_messages = false;

	/**
	 * @param initial_ring the RingDescription of the initial ring
	 */
	public ElasticLearnerRole(RingDescription initial_ring) {
		RingManager rm = initial_ring.getRingManager();
		node = rm.getPaxosNode();
		replication_group = node.getGroupID();
		logger.info("ElasticLearnerRole replication group " + replication_group);
		rings.add(rm.getRingID());
		ringmap.put(rm.getRingID(),initial_ring);
		deliverRing = rm.getRingID();
		logger.info("ElasticLearnerRole initial ring " + deliverRing);
		if(rm.getConfiguration().containsKey(ConfigKey.deliver_skip_messages)){
			if(rm.getConfiguration().get(ConfigKey.deliver_skip_messages).contains("1")){
				deliver_skip_messages = true;
			}
			logger.info("ElasticLearnerRole deliver_skip_messages: " + (deliver_skip_messages ? "enabled" : "disabled"));
		}
	}

	@Override
	public void run() {
		// create initial learner
		int initial_ring = rings.get(0);
		startLearner(initial_ring);
		int rr_count = 0;
		while(true){
			try{
				if(skip_count[deliverRing] > 0){
					rr_count++;
					skip_count[deliverRing]--;
					v_count[deliverRing]++;
					//logger.debug("ElasticLearnerRole " + ringmap.get(deliverRing).getNodeID() + " ring " + deliverRing + " skiped a value (" + skip_count[deliverRing] + " skips left)");
				}else{
					Decision d = learner[deliverRing].getDecisions().take();
					if(d.getValue() != null && d.getValue().isControl()){
						v_count[deliverRing]++;
						// control message
						try {
							Control control = Control.fromWire(d.getValue().getValue());
							ControlType type = control.getType();
							int group = control.getGroupID();
							int ring = control.getRingID();
							if(type == ControlType.Prepare){
								long time = System.nanoTime();
								logger.info("ElasticLearner received prepare: " + ring + " for group " + group);
								if(learner[ring] == null && replication_group == group){
									newRing = ring;
									List<PaxosRole> rl = new ArrayList<PaxosRole>();
									rl.add(PaxosRole.Learner);
									final RingDescription rd = new RingDescription(newRing,rl);
									ringmap.put(newRing,rd);
									final Thread start = new Thread("Prepare " + newRing){		    			
						    			@Override
						    			public void run() {
						    				if(!node.updateRing(rd)){
												logger.error("ElasticLearnerRole failed to create Learner in ring " + newRing);
											}
											startLearner(newRing);
						    			}
									};
									start.start();									
								}
								logger.info("ElasticLearner prepare end in " + (System.nanoTime()-time));
							}else if(type == ControlType.Subscribe){
								logger.info("ElasticLearner received subscribe: " + ring + " for group " + group);
								if(learner[ring] == null && replication_group == group){
									newRing = ring;
									List<PaxosRole> rl = new ArrayList<PaxosRole>();
									rl.add(PaxosRole.Learner);
									RingDescription rd = new RingDescription(newRing,rl);
									ringmap.put(newRing,rd);
									if(!node.updateRing(rd)){
										logger.error("ElasticLearnerRole failed to create Learner in ring " + newRing);
									}
									startLearner(newRing);
								}
								if(learner[ring] != null && replication_group == group){
									while(true){
										Decision d2 = learner[newRing].getDecisions().take();
										if(d2.getValue() != null && d2.getValue().isSkip()){
											try {
												long skip = Long.parseLong(new String(d2.getValue().getValue()));
												v_count[newRing] = v_count[newRing] + skip;
											}catch (NumberFormatException e) {
												logger.error("ElasticLearnerRole received incomplete SKIP message in new ring! -> " + d2,e);
											}										
										}
										else{
											v_count[newRing]++;
										}
										if(d2 != null && d2.getValue().isControl()){
											Control control2 = Control.fromWire(d2.getValue().getValue());
											if(control.equals(control2)){
												break;
											}
										}
									}
									long maxPosition = 0;
									if(v_count[deliverRing] > maxPosition){
										maxPosition = v_count[deliverRing];
									}
									if(v_count[newRing] > maxPosition){
										maxPosition = v_count[newRing];
									}
									v_subscribe = maxPosition+1;
									// align the new stream
									if(v_count[newRing] < maxPosition){
										while(true){
											Decision d2 = learner[newRing].getDecisions().take();
											if(d2.getValue() != null && d2.getValue().isSkip()){
												try {
													long skip = Long.parseLong(new String(d2.getValue().getValue()));
													v_count[newRing] = v_count[newRing] + skip;
												}catch (NumberFormatException e) {
													logger.error("ElasticLearnerRole received incomplete SKIP message in new ring! -> " + d2,e);
												}										
											}
											else{
												v_count[newRing]++;
											}
											if(v_count[newRing] >= maxPosition){ // >= because of skips
												break;
											}
										}
									}
									deliverRing = getRingSuccessor(deliverRing);
									logger.info("ElasticLearner subscribe to ring " + newRing + " in group " + group + " at position " + v_subscribe);
								}else{
									rr_count++; //skip it in queue
								}
							}else if(type == ControlType.Unsubscribe){
								logger.info("ElasticLearner received unsubscribe for ring " + ring + " in group " + group);
								if(learner[ring] != null && replication_group == group){
									rings.remove(new Integer(ring)); // remove entry not index position
									final int close = ring;
									final Thread stop = new Thread("Prepare " + newRing){		    			
						    			@Override
						    			public void run() {
											((LearnerRole)ringmap.get(close).getRingManager().getNetwork().getLearner()).close();
											try {
												ringmap.get(close).getRingManager().close();
											} catch (InterruptedException e) {
											}
						    			}
									};
									stop.start();									
									learner[ring] = null;
									deliverRing = minRing(rings); 
									logger.info("ElasticLearner removed ring " + ring + " at position " + v_count[ring]);
									v_count[ring] = 0;
								}								
							}
						}catch (NumberFormatException e) {
							logger.error("ElasticLearnerRole received incomplete subscribe message! -> " + d,e);
						}
						if(deliver_skip_messages){
							values.add(d);
						}
					}else if(d.getValue() != null && d.getValue().isSkip()){
						// skip message
						try {
							long skip = Long.parseLong(new String(d.getValue().getValue()));
							skip_count[deliverRing] = skip_count[deliverRing] + skip;
						}catch (NumberFormatException e) {
							logger.error("ElasticLearnerRole received incomplete SKIP message! -> " + d,e);
						}
						if(deliver_skip_messages){
							values.add(d);
						}
					}else{
						rr_count++;
						v_count[deliverRing]++;
						values.add(d); // deliver an actual proposed value
					}
				}
				if(rr_count >= 1){ // removed variable M
					rr_count = 0;
					deliverRing = getRingSuccessor(deliverRing);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;				
			}
		}
	}

	private void startLearner(int ringID){
		RingManager ring = ringmap.get(ringID).getRingManager();
		Role r = new LearnerRole(ring);
		learner[ringID] = (LearnerRole) r;
		logger.debug("ElasticLearnerRole register role: " + PaxosRole.Learner + " at node " + ring.getNodeID() + " in ring " + ring.getRingID());
		ring.registerRole(PaxosRole.Learner);		
		Thread t = new Thread(r);
		t.setName(PaxosRole.Learner + "-" + ringID);
		t.start();
		skip_count[ringID] = 0;
	}
	
	private int getRingSuccessor(int id){
		boolean add = true;
		for(Integer r : rings){
			if(v_count[r] != v_subscribe-1){
				add = false;
			}
		}
		if(add){
			rings.add(newRing);
			return minRing(rings);
		}
		int pos = rings.indexOf(new Integer(id));
		if(pos+1 >= rings.size()){
			return rings.get(0);
		}else{
			return rings.get(pos+1);
		}
	}

	private int minRing(List<Integer> rings) {
		int min = Integer.MAX_VALUE;
		for(int i : rings){
			if(i < min){
				min = i;
			}
		}
		return min;
	}
	
	@Override
	public BlockingQueue<Decision> getDecisions() {
		return values;
	}

	public void setSafeInstance(Integer ring, Long instance) {
		learner[ring].setSafeInstance(ring,instance);
	}

}
