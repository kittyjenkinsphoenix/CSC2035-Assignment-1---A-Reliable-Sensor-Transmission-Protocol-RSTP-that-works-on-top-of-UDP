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
		// Count total number of readings (lines) in the input CSV file
		int lines = 0;
		try (BufferedReader br = new BufferedReader(new FileReader(this.inputFile))) {
			while (br.readLine() != null) lines++;
		} catch (IOException e) {
			System.out.println("CLIENT: Error Reading Input File: " + e.getMessage());
			if (this.socket != null && !this.socket.isClosed()) this.socket.close();
			System.exit(0);
		}

		// store the result in the global variable
		this.fileTotalReadings = lines;

		// assemble payload: <fileTotalReadings>,<outputFileName>,<patchSize>
		String payload = this.fileTotalReadings + "," + this.outputFileName + "," + this.maxPatchSize;

		// create Meta segment (seqNum = 0)
		Segment metaSeg = new Segment(0, SegmentType.Meta, payload, payload.length());

	// Print Status Messages
	System.out.println("CLIENT: META [SEQ#" + metaSeg.getSeqNum() + "] (Number Of Readings:" + this.fileTotalReadings + ", File Name:" + this.outputFileName + ", Patch Size:" + this.maxPatchSize + ")");

		// serialize and send the segment to the server
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
		// If there are no more readings to send, just return
		if (this.sentReadings >= this.fileTotalReadings) {
			return;
		}

		// Read up to maxPatchSize readings from the input file starting at sentReadings
		StringBuilder payloadBuilder = new StringBuilder();
		int linesRead = 0;
		try (BufferedReader br = new BufferedReader(new FileReader(this.inputFile))) {
			// skip already sent lines
			for (int i = 0; i < this.sentReadings; i++) {
				if (br.readLine() == null) break;
			}

			String line;
			while (linesRead < this.maxPatchSize && (line = br.readLine()) != null) {
				// parse the CSV line into a Reading object: sensorId,timestamp,value1,value2,value3
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
					// Malformed line; skip it
				}
			}
		} catch (IOException e) {
			System.out.println("CLIENT: Error Reading Input File: " + e.getMessage());
			if (this.socket != null && !this.socket.isClosed()) this.socket.close();
			System.exit(0);
		}

		// If nothing was read, return
		if (linesRead == 0) return;

		String payload = payloadBuilder.toString();

		// Determine seqNum: first data segment should have seqNum 1 and alternate thereafter
		int seqNum = (this.totalSegments % 2 == 0) ? 1 : 0;

		// Create Data segment using constructor so checksum is calculated
		Segment dataSegment = new Segment(seqNum, SegmentType.Data, payload, payload.length());

		// Print status message (Title Case)
		System.out.println("CLIENT: Send: DATA [SEQ#" + dataSegment.getSeqNum() + "](Size:" + dataSegment.getSize() + ", Crc: " + dataSegment.getChecksum() + ", Content:" + dataSegment.getPayLoad() + ")");

		// Serialize and send the data segment
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

		// Update totalSegments (count of segments client sent)
		this.totalSegments++;
	}

	/* 
	 * This method receives the current Ack segment (ackSeg) from the server 
	 * See coursework specification for full details.
	 */
	public boolean receiveAck() { 
		System.exit(0);
		return false;
	}

	/* 
	 * This method starts a timer and does re-transmission of the Data segment 
	 * See coursework specification for full details.
	 */
	public void startTimeoutWithRetransmission()   {  
		System.exit(0);
	}


	/* 
	 * This method is used by the server to receive the Data segment in Lost Ack mode
	 * See coursework specification for full details.
	 */
	public void receiveWithAckLoss(DatagramSocket serverSocket, float loss)  {
		System.exit(0);
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
