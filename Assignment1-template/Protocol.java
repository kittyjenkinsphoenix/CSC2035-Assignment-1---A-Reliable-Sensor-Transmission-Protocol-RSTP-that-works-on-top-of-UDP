// Comments Were Produced By Copilot

/*
 * Replace the following string of 0s with your student number
 * 000000000
 */
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class Protocol {

	static final String  NORMAL_MODE="nm"   ;         // normal transfer mode: (for Part 1 and 2)
	static final String	 TIMEOUT_MODE ="wt"  ;        // timeout transfer mode: (for Part 3)
	static final String	 LOST_MODE ="wl"  ;           // lost Ack transfer mode: (for Part 4)
	static final int DEFAULT_TIMEOUT =1000  ;         // default timeout in milliseconds (for Part 3)
	static final int DEFAULT_RETRIES =4  ;            // default number of consecutive retries (for Part 3)
	public static final int MAX_Segment_SIZE = 4096;  //the max segment size that can be used when creating the received packet's buffer

	/*
	 * The following attributes control the execution of the transfer protocol and provide access to the 
	 * resources needed for the transfer 
	 * 
	 */ 

	private InetAddress ipAddress;      // the address of the server to transfer to. This should be a well-formed IP address.
	private int portNumber; 		    // the  port the server is listening on
	private DatagramSocket socket;      // the socket that the client binds to

	private File inputFile;            // the client-side CSV file that has the readings to transfer  
	private String outputFileName ;    // the name of the output file to create on the server to store the readings
	private int maxPatchSize;		   // the patch size - no of readings to be sent in the payload of a single Data segment

	private Segment dataSeg   ;        // the protocol Data segment for sending Data segments (with payload read from the csv file) to the server 
	private Segment ackSeg  ;          // the protocol Ack segment for receiving ACK segments from the server

	private int timeout;              // the timeout in milliseconds to use for the protocol with timeout (for Part 3)
	private int maxRetries;           // the maximum number of consecutive retries (retransmissions) to allow before exiting the client (for Part 3)(This is per segment)
	private int currRetry;            // the current number of consecutive retries (retransmissions) following an Ack loss (for Part 3)(This is per segment)

	private int fileTotalReadings;    // number of all readings in the csv file
	private int sentReadings;         // number of readings successfully sent and acknowledged
	private int totalSegments;        // total segments that the client sent to the server

	// Shared Protocol instance so Client and Server access and operate on the same values for the protocol’s attributes (the above attributes).
	public static Protocol instance = new Protocol();

	/**************************************************************************************************************************************
	 **************************************************************************************************************************************
	 * For this assignment, you have to implement the following methods:
	 *		sendMetadata()
	 *      readandSend()
	 *      receiveAck()
	 *      startTimeoutWithRetransmission()
	 *		receiveWithAckLoss()
	 * Do not change any method signatures, and do not change any other methods or code provided.
	 ***************************************************************************************************************************************
	 **************************************************************************************************************************************/
	/* 
	 * This method sends protocol metadata to the server.
	 * See coursework specification for full details.	
	 */
	public void sendMetadata()   { 
		// Count Total Number Of Readings (Lines) In The Input CSV File
		int lines = 0;
		try (BufferedReader br = new BufferedReader(new FileReader(this.inputFile))) {
			while (br.readLine() != null) lines++;
		} catch (IOException e) {
			System.out.println("CLIENT: Error Reading Input File: " + e.getMessage());
			if (this.socket != null && !this.socket.isClosed()) this.socket.close();
			System.exit(0);
		}

		// Store The Result In The Global Variable
		this.fileTotalReadings = lines;

		// Assemble Payload: <fileTotalReadings>,<outputFileName>,<patchSize>
		String payload = this.fileTotalReadings + "," + this.outputFileName + "," + this.maxPatchSize;

		// Create Meta Segment (SeqNum = 0)
		Segment metaSeg = new Segment(0, SegmentType.Meta, payload, payload.length());

		// Print Status Messages
		System.out.println("CLIENT: META [SEQ#" + metaSeg.getSeqNum() + "] (Number Of Readings:" + this.fileTotalReadings + ", File Name:" + this.outputFileName + ", Patch Size:" + this.maxPatchSize + ")");

		// Serialise And Send The Segment To The Server
		try {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			ObjectOutputStream os = new ObjectOutputStream(outputStream);
			os.writeObject(metaSeg);
			byte[] data = outputStream.toByteArray();
			DatagramPacket packet = new DatagramPacket(data, data.length, this.ipAddress, this.portNumber);
			this.socket.send(packet);
		} catch (IOException e) {
			System.out.println("CLIENT: Error Sending Metadata: " + e.getMessage());
			if (this.socket != null && !this.socket.isClosed()) this.socket.close();
			System.exit(0);
		}
	} 


	/* 
	 * This method read and send the next data segment (dataSeg) to the server. 
	 * See coursework specification for full details.
	 */
	public void readAndSend() { 
		// If There Are No More Readings To Send, Just Return
		if (this.sentReadings >= this.fileTotalReadings) {
			return;
		}

		// Read Up To MaxPatchSize Readings From The Input File Starting At SentReadings
		StringBuilder payloadBuilder = new StringBuilder();
		int linesRead = 0;
		try (BufferedReader br = new BufferedReader(new FileReader(this.inputFile))) {
			// Skip Already Sent Lines
			for (int i = 0; i < this.sentReadings; i++) {
				if (br.readLine() == null) break;
			}

			String line;
			while (linesRead < this.maxPatchSize && (line = br.readLine()) != null) {
				// Parse The CSV Line Into A Reading Object: SensorId,Timestamp,Value1,Value2,Value3
				String[] parts = line.split(",");
				if (parts.length >= 5) {
					String sensorId = parts[0].trim();
					long timestamp = Long.parseLong(parts[1].trim());
					float[] values = new float[3];
					values[0] = Float.parseFloat(parts[2].trim());
					values[1] = Float.parseFloat(parts[3].trim());
					values[2] = Float.parseFloat(parts[4].trim());
					Reading r = new Reading(sensorId, timestamp, values);
					if (payloadBuilder.length() > 0) payloadBuilder.append(";");
					payloadBuilder.append(r.toString());
					linesRead++;
				} else {
					System.out.println("CLIENT: Invalid CSV Line: " + line);
				}
			}
		} catch (IOException e) {
			System.out.println("CLIENT: Error Reading Input File: " + e.getMessage());
			if (this.socket != null && !this.socket.isClosed()) this.socket.close();
			System.exit(0);
		}

		// If Nothing Was Read, Return
		if (linesRead == 0) return;

		String payload = payloadBuilder.toString();

		// Determine SeqNum: First Data Segment Should Have SeqNum 1 And Alternate Thereafter
		int seqNum = (this.totalSegments % 2 == 0) ? 1 : 0;

	// Create Data Segment Using Constructor So Checksum Is Calculated
	Segment dataSegment = new Segment(seqNum, SegmentType.Data, payload, payload.length());

	// Store The Current Data Segment So Other Methods Can Access It
	this.dataSeg = dataSegment;

		// Print Status Message
		System.out.println("CLIENT: Send: DATA [SEQ#" + dataSegment.getSeqNum() + "](Size:" + dataSegment.getSize() + ", Crc: " + dataSegment.getChecksum() + ", Content:" + dataSegment.getPayLoad() + ")");

		// Serialise And Send The Data Segment
		try {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			ObjectOutputStream os = new ObjectOutputStream(outputStream);
			os.writeObject(dataSegment);
			byte[] data = outputStream.toByteArray();
			DatagramPacket packet = new DatagramPacket(data, data.length, this.ipAddress, this.portNumber);
			this.socket.send(packet);
		} catch (IOException e) {
			System.out.println("CLIENT: Error Sending Data Segment: " + e.getMessage());
			if (this.socket != null && !this.socket.isClosed()) this.socket.close();
			System.exit(0);
		}

		// Update TotalSegments (Count Of Segments Client Sent)
		this.totalSegments++;
	}

	/* 
	 * This method receives the current Ack segment (ackSeg) from the server 
	 * See coursework specification for full details.
	 */
	public boolean receiveAck() { 
		byte[] buf = new byte[MAX_Segment_SIZE];
		DatagramPacket incomingPacket = new DatagramPacket(buf, buf.length);
		try {
			// Wait For Ack From Server
			this.socket.receive(incomingPacket);

			// Deserialize The Incoming Segment
			byte[] data = incomingPacket.getData();
			java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(data);
			java.io.ObjectInputStream is = new java.io.ObjectInputStream(in);
			Segment receivedAck = (Segment) is.readObject();

			// Store Ack Segment
			this.ackSeg = receivedAck;

			// Print Status Message
			System.out.println("CLIENT: Receive: ACK [SEQ#" + receivedAck.getSeqNum() + "]");

			// Check Sequence Number
			if (this.dataSeg == null || receivedAck.getSeqNum() != this.dataSeg.getSeqNum()) {
				return false;
			}

			// Update SentReadings Based On The Payload Of The Last Sent Data Segment
			String payload = this.dataSeg.getPayLoad();
			int count = 0;
			if (payload != null && !payload.isEmpty()) {
				count = payload.split(";").length;
			}
			this.sentReadings += count;
				
			// Separator Line (Visual) 
			// Produced By Copilot
			System.out.println("***************************************************************************************************");

			// If All Readings Have Been Acknowledged, Print Total Segments And Exit
			if (this.sentReadings >= this.fileTotalReadings) {
				System.out.println("Total Segments: " + this.totalSegments);
				System.exit(0);
			}

			return true;

		} catch (java.net.SocketTimeoutException e) {
			// Timeout Waiting For ACK - Return False So Caller Can Retransmit
			return false;
		} catch (java.io.IOException e) {
			System.out.println("CLIENT: Error Receiving Ack: " + e.getMessage());
			if (this.socket != null && !this.socket.isClosed()) this.socket.close();
			System.exit(0);
		} catch (ClassNotFoundException e) {
			System.out.println("CLIENT: Error Receiving Ack: " + e.getMessage());
			if (this.socket != null && !this.socket.isClosed()) this.socket.close();
			System.exit(0);
		}
		return false;
	}

	/* 
	 * This method starts a timer and does re-transmission of the Data segment 
	 * See coursework specification for full details.
	 */
	public void startTimeoutWithRetransmission()   {  
		// Ensure There Is A Data Segment To Retransmit
		if (this.dataSeg == null) return;

		try {
			// Set Socket Timeout
			this.socket.setSoTimeout(this.timeout);

			// Loop Until Ack Received Or Max Retries Exceeded
			while (true) {
				boolean ackReceived = receiveAck();
				if (ackReceived) {
					// Reset Current Retry Counter
					this.currRetry = 0;
					// Disable Timeout (Blocking Receive)
					this.socket.setSoTimeout(0);
					break;
				}

				// Not Acknowledged - Retransmit
				// Produced By Copilot
				this.currRetry++;
				if (this.currRetry > this.maxRetries) {
					System.out.println("CLIENT: Maximum Retries Exceeded. Exiting.");
					if (this.socket != null && !this.socket.isClosed()) this.socket.close();
					System.exit(0);
				}

				// Inform User About Timeout And Retransmission
				System.out.println("CLIENT: TIMEOUT ALERT");
				System.out.println("CLIENT: Re-Sending The Same Segment Again, Current Retry " + this.currRetry);

				// Resend The Same Data Segment
				try {
					ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
					ObjectOutputStream os = new ObjectOutputStream(outputStream);
					os.writeObject(this.dataSeg);
					byte[] data = outputStream.toByteArray();
					DatagramPacket packet = new DatagramPacket(data, data.length, this.ipAddress, this.portNumber);
					this.socket.send(packet);
					// Update Total Segments Count
					this.totalSegments++;
				} catch (IOException e) {
					System.out.println("CLIENT: Error Resending Data Segment: " + e.getMessage());
					if (this.socket != null && !this.socket.isClosed()) this.socket.close();
					System.exit(0);
				}
			}
		} catch (SocketException e) {
			System.out.println("CLIENT: Socket Error: " + e.getMessage());
			System.exit(0);
		}
	}


	/* 
	 * This method is used by the server to receive the Data segment in Lost Ack mode
	 * See coursework specification for full details.
	 */
	public void receiveWithAckLoss(DatagramSocket serverSocket, float loss)  {
		byte[] buf = new byte[MAX_Segment_SIZE];

		// Create A Temporary List To Store The Readings
		java.util.List<String> receivedLines = new java.util.ArrayList<>();

		// Track The Number Of Correctly Received Readings
		int readingCount = 0;

		// Statistics For Efficiency
		int totalBytesReceived = 0; // Includes Retransmissions
		int usefulBytes = 0; // Bytes Of Useful Data (First-Time Accepted Segments)

		// Expected Seq Number Starts At 1 (Meta Used Seq 0)
		int expectedSeq = 1;
		int lastCorrectSeq = -1;

		System.out.println("SERVER: Waiting For Data With Ack Loss Simulation");

		try {
			// Wait Up To 2000Ms For Packets When Client May Have Given Up
			serverSocket.setSoTimeout(2000);

			while (true) { // Produced By Copilot
				DatagramPacket incomingPacket = new DatagramPacket(buf, buf.length);
				try {
					serverSocket.receive(incomingPacket);
				} catch (java.net.SocketTimeoutException ste) {
					// No Packet Received Within Timeout - Assume Client Exited After Retries
					System.out.println("SERVER: No Packets Received For 2000ms. Exiting.");
					break;
				}

				Segment serverDataSeg = new Segment();
				byte[] data = incomingPacket.getData();
				java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(data);
				java.io.ObjectInputStream is = new java.io.ObjectInputStream(in);

				try {
					serverDataSeg = (Segment) is.readObject();
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
					continue;
				}

				System.out.println("SERVER: Receive: DATA [SEQ#"+ serverDataSeg.getSeqNum()+ "]("+"size:"+serverDataSeg.getSize()+", crc: "+serverDataSeg.getChecksum()+", content:"  + serverDataSeg.getPayLoad()+")");

				// Count Every Received Data Segment Bytes
				totalBytesReceived += serverDataSeg.getSize();

				long x = serverDataSeg.calculateChecksum();

				if (serverDataSeg.getType() == SegmentType.Data && x == serverDataSeg.getChecksum()) {
					System.out.println("SERVER: Calculated Checksum Is " + x + "  VALID");

					// If SeqNum Is Expected, Accept And Store Payload
					if (serverDataSeg.getSeqNum() == expectedSeq) {
						String[] lines = serverDataSeg.getPayLoad().split(";");
						receivedLines.add("Segment ["+ serverDataSeg.getSeqNum() + "] has "+ lines.length + " Readings");
						receivedLines.addAll(java.util.Arrays.asList(lines));
						receivedLines.add("");

						readingCount += lines.length;

						// Useful Bytes Increased Only For The First-Time Accepted Segments
						usefulBytes += serverDataSeg.getSize();

						// Extract Client Address And Port
						InetAddress iPAddress = incomingPacket.getAddress();
						int port = incomingPacket.getPort();

						// Record Last Correct Seq
						lastCorrectSeq = serverDataSeg.getSeqNum();

						// Decide Whether To Simulate Ack Loss
						if (isLost(loss)) {
							System.out.println("SERVER: Simulating ACK Loss. ACK[SEQ#" + serverDataSeg.getSeqNum() + "] Is Lost.");
							System.out.println("******************************");
						} else {
							// send ack normally
							Server.sendAck(serverSocket, iPAddress, port, serverDataSeg.getSeqNum());
						}

						// Flip Expected Seq
						expectedSeq = (expectedSeq == 1) ? 0 : 1;

					} else {
						// Duplicate Data Segment
						System.out.println("Duplicate DATA Is Detected");
						System.out.println("Sending An Ack Of The Previous Segment");

						// Resend Ack For Last Correct Seq (If Known)
						InetAddress iPAddress = incomingPacket.getAddress();
						int port = incomingPacket.getPort();
						if (lastCorrectSeq >= 0) {
							if (isLost(loss)) {
								System.out.println("SERVER: Simulating ACK Loss. ACK[SEQ#" + lastCorrectSeq + "] Is Lost.");
								System.out.println("******************************");
							} else {
								Server.sendAck(serverSocket, iPAddress, port, lastCorrectSeq);
							}
						}
					}

				} else if (serverDataSeg.getType() == SegmentType.Data && x != serverDataSeg.getChecksum()) {
					System.out.println("SERVER: Calculated Checksum Is " + x + "  INVALID");
					System.out.println("SERVER: Not Sending Any ACK ");
					System.out.println("***************************");
				}

				// If All Readings Are Received, Then Write The Readings To The File And Finish
				if (this.getOutputFileName() != null && readingCount >= this.getFileTotalReadings()) {
					Server.writeReadingsToFile(receivedLines, this.getOutputFileName());
					break;
				}
			}

		} catch (IOException e) {
			System.out.println("SERVER: Error: " + e.getMessage());
		} finally {
			// Compute And Print Efficiency If Some Useful Bytes Were Recorded
			if (totalBytesReceived > 0) {
				System.out.println("Total Bytes :" + totalBytesReceived);
				System.out.println("Useful Bytes :" + usefulBytes);
				double efficiency = ((double) usefulBytes / (double) totalBytesReceived) * 100.0;
				System.out.println("Efficiency : " + efficiency + " %");
			}

			try { serverSocket.close(); } catch (Exception ex) {}
		}
	}


	/*************************************************************************************************************************************
	 **************************************************************************************************************************************
	 **************************************************************************************************************************************
	These methods are implemented for you .. Do NOT Change them 
	 **************************************************************************************************************************************
	 **************************************************************************************************************************************
	 **************************************************************************************************************************************/	 
	/* 
	 * This method initialises ALL the 14 attributes needed to allow the Protocol methods to work properly
	 */
	public void initProtocol(String hostName , String portNumber, String fileName, String outputFileName, String batchSize) throws UnknownHostException, SocketException {
		instance.ipAddress = InetAddress.getByName(hostName);
		instance.portNumber = Integer.parseInt(portNumber);
		instance.socket = new DatagramSocket();

		instance.inputFile = checkFile(fileName); //check if the CSV file does exist
		instance.outputFileName =  outputFileName;
		instance.maxPatchSize= Integer.parseInt(batchSize);

		instance.dataSeg = new Segment(); //initialise the data segment for sending readings to the server
		instance.ackSeg = new Segment();  //initialise the ack segment for receiving Acks from the server

		instance.fileTotalReadings = 0; 
		instance.sentReadings=0;
		instance.totalSegments =0;

		instance.timeout = DEFAULT_TIMEOUT;
		instance.maxRetries = DEFAULT_RETRIES;
		instance.currRetry = 0;		 
	}


	/* 
	 * check if the csv file does exist before sending it 
	 */
	private static File checkFile(String fileName)
	{
		File file = new File(fileName);
		if(!file.exists()) {
			System.out.println("CLIENT: File Does Not Exist"); 
			System.out.println("CLIENT: Exit."); 
			System.exit(0);
		}
		return file;
	}

	/* 
	 * returns true with the given probability to simulate network errors (Ack loss)(for Part 4)
	 */
	private static Boolean isLost(float prob) 
	{ 
		double randomValue = Math.random();  //0.0 to 99.9
		return randomValue <= prob;
	}

	/* 
	 * getter and setter methods	 *
	 */
	public String getOutputFileName() {
		return outputFileName;
	} 

	public void setOutputFileName(String outputFileName) {
		this.outputFileName = outputFileName;
	} 

	public int getMaxPatchSize() {
		return maxPatchSize;
	} 

	public void setMaxPatchSize(int maxPatchSize) {
		this.maxPatchSize = maxPatchSize;
	} 

	public int getFileTotalReadings() {
		return fileTotalReadings;
	} 

	public void setFileTotalReadings(int fileTotalReadings) {
		this.fileTotalReadings = fileTotalReadings;
	}

	public void setDataSeg(Segment dataSeg) {
		this.dataSeg = dataSeg;
	}

	public void setAckSeg(Segment ackSeg) {
		this.ackSeg = ackSeg;
	}

	public void setCurrRetry(int currRetry) {
		this.currRetry = currRetry;
	}

}
