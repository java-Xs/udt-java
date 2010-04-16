package udt;

import java.util.List;

import javax.swing.text.Utilities;

import udt.util.UDTStatistics;
import udt.util.Util;

public class UDTCongestionControl {

	private final UDTSession session;
	private final UDTStatistics statistics;
	//round trip time in microseconds
	private long roundTripTime=10*Util.getSYNTime();
	
	//rate in packets per second
	private long packetArrivalRate=100;
	
	//link capacity in packets per second
	private long estimatedLinkCapacity;
	
	long maxControlWindowSize=128;

	// Packet sending period = packet send interval, in microseconds
	private double packetSendingPeriod=1;              
	// Congestion window size, in packets
	private long congestionWindowSize=16;
	
	//number of packets to be increased in the next SYN period
	private double numOfIncreasingPacket;		
	
	//last rate increase time (microsecond value)
	long lastRateIncreaseTime=Util.getCurrentTime();
	
	/*if in slow start phase*/
	boolean slowStartPhase=true;
	
	/*last ACKed seq no*/
	long lastAckSeqNumber=-1;
	
	/*max packet seq. no. sent out when last decrease happened*/
	private	long lastDecreaseSeqNo;

	//value of packetSendPeriod when last decrease happened
	long lastDecreasePeriod;
	
	//NAK counter
	long nACKCount=1;
	
	//number of decreases in a congestion epoch
	long congestionEpochDecreaseCount=1;
	
	//random threshold on decrease by number of loss events
	long decreaseRandom; 
	
	//average number of NAKs per congestion
	long averageNACKNum;

	public UDTCongestionControl(UDTSession session){
		this.session=session;
		this.statistics=session.getStatistics();
		lastDecreaseSeqNo= session.getInitialSequenceNumber()-1;
		init();
	}

	/**
	 * Callback function to be called (only) at the start of a UDT connection.
	 * when the UDT socket is conected 
	 */
	public void init() {
		
	}

	public void setRTT(long rtt, long rttVar){
		this.roundTripTime=rtt;
	}

	public void setPacketArrivalRate(long rate, long linkCapacity){
		this.packetArrivalRate=rate;
		this.estimatedLinkCapacity=linkCapacity;
	}

	/**
	 * Inter-packet interval in seconds
	 * @return 
	 */
	public double getSendInterval(){
		return packetSendingPeriod ;
	}
	
	/**
	 * congestionWindowSize
	 * @return
	 */
	protected long getCongestionWindowSize(){
		return congestionWindowSize;
	}

	/**
	 * Callback function to be called when an ACK packet is received.
	 * @param ackSeqno: the data sequence number acknowledged by this ACK.
	 * see spec. page(16-17)
	 */
	public void onACK(long ackSeqno){
		//the fixed size of a UDT packet 
		long maxSegmentSize=UDPEndPoint.DATAGRAM_SIZE;
		
		//1.if it is  in slow start phase,set the congestion window size 
		//to the product of packet arrival rate and(rtt +SYN)
		double A=packetArrivalRate*(roundTripTime+Util.getSYNTime());
		if(slowStartPhase){
			congestionWindowSize=16;
			slowStartPhase=false;
			return;
		}else{
			congestionWindowSize=(long)A+16;
		}

		//4.compute the number of sent packets to be increase in the next SYN period
		//and update the send intervall
		if(estimatedLinkCapacity<= packetArrivalRate){
			numOfIncreasingPacket= 1/maxSegmentSize;
		}else{
			numOfIncreasingPacket=computeNumOfIncreasingPacket();
		}
		//4.update the send period :
		packetSendingPeriod=packetSendingPeriod*Util.getSYNTimeSeconds()/
					(packetSendingPeriod*numOfIncreasingPacket+Util.getSYNTimeSeconds());
		statistics.setSendPeriod(packetSendingPeriod);
	}

	final double Beta=0.0000015/UDPEndPoint.DATAGRAM_SIZE;
	private double computeNumOfIncreasingPacket (){
		long B,C,S;
		B=estimatedLinkCapacity;
		C=packetArrivalRate;
		S=UDPEndPoint.DATAGRAM_SIZE;
		
		double logBase10=Math.log10( S*(B-C)*8 );
		double power10 = Math.pow( 10.0,Math.ceil (logBase10) )* Beta;
		double inc = Math.max(power10, 1/S);
		return inc;
	}
	
	/**
	 *  Callback function to be called when a loss report is received.
	 * @param lossInfo:list of sequence number of packets, in the format describled in packet.cpp.
	 */
	public void onNAK(List<Integer>lossInfo){
		long firstBiggestlossSeqNo=lossInfo.get(lossInfo.size()-1);
		long currentMaxSequenceNumber=session.getSocket().getSender().getCurrentSequenceNumber();
		lastAckSeqNumber = currentMaxSequenceNumber;
		nACKCount++;
		/*1) If it is in slow start phase, set inter-packet interval to 
      	   1/recvrate. Slow start ends. Stop. */
		if(slowStartPhase){
			if(packetArrivalRate>0){
				packetSendingPeriod = 1e6/packetArrivalRate;
			}
			else{
				packetSendingPeriod=congestionWindowSize*(roundTripTime+Util.getSYNTime());
			}
			slowStartPhase = false;
			return;
		}

		//start new congestion epoch
		if(firstBiggestlossSeqNo>lastDecreaseSeqNo){
			// 2)If this NAK starts a new congestion epoch
			// -increase inter-packet interval
			packetSendingPeriod = Math.ceil(packetSendingPeriod*1.125);
			// -Update AvgNAKNum(the average number of NAKs per congestion)
			averageNACKNum = (int)Math.ceil(averageNACKNum*0.875 + nACKCount*0.125);
			// -reset NAKCount to 1, 
			nACKCount=1;
			/* - compute DecRandom to a random (average distribution) number between 1 and AvgNAKNum.. */
			decreaseRandom =(int)Math.ceil((averageNACKNum-1)*Math.random()+1);
			// -Update LastDecSeq
			lastDecreaseSeqNo = currentMaxSequenceNumber;
			// -Stop.
			statistics.setSendPeriod(packetSendingPeriod);
			return;
		}

		//* 3) If DecCount <= 5, and NAKCount == DecCount * DecRandom: 
		if(congestionEpochDecreaseCount<=5 && 
				nACKCount==congestionEpochDecreaseCount*decreaseRandom){
			// a. Update SND period: SND = SND * 1.125; 
			packetSendingPeriod = Math.ceil(packetSendingPeriod*1.125);
			// b. Increase DecCount by 1; 
			congestionEpochDecreaseCount++;
			// c. Record the current largest sent sequence number (LastDecSeq).
			lastDecreaseSeqNo= currentMaxSequenceNumber;
			statistics.setSendPeriod(packetSendingPeriod);
			return;
		}
	}

	/**
	 * Callback function to be called when a timeout event occurs
	 */
	public void onTimeout(){}

	/**
	 * Callback function to be called when a data is sent.
	 * @param packetSeqNo: the data sequence number.
	 */
	public void onPacketSend(long packetSeqNo){}

	/**
	 * Callback function to be called when a data is received.
	 * @param packetSeqNo: the data sequence number.
	 */
	public void onPacketReceive(long packetSeqNo){}
	/**
	 * Callback function to be called when a UDT connection is closed.
	 */
	public void close(){}


}
