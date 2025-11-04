# CSC2035 Assignment 1 — RSTP Over UDP

This repository contains the assignment template and a working implementation of the Reliable Sensor
Transmission Protocol (RSTP) that runs on top of UDP. The protocol transfers sensor readings from a
client to a server using segmented UDP packets and implements Stop-and-Wait ARQ, timeout-based
retransmission and simulated ACK loss for testing.

This README explains how to compile and run the supplied Java programs on Windows (PowerShell),
what modes are available, and how to test the implementation.

## Contents

- `Client.java` — Client application (Do Not Edit)
- `Server.java` — Server application (Do Not Edit)
- `Protocol.java` — Protocol logic (Implemented methods live here; this is the file to submit for the assignment)
- `Segment.java` — Segment definition (Do Not Edit)
- `Reading.java` — Reading representation (Do Not Edit)
- `data.csv` — Example CSV file with sensor readings

## Prerequisites

- Java JDK installed and `javac`/`java` available on PATH
- Windows PowerShell (examples below use PowerShell syntax)

## Compile

Open PowerShell, change to the `Assignment1-template` folder and compile all Java files:

```powershell
cd "C:\Users\<you>\...\Assignment1-template"
javac *.java
```

If compilation succeeds you will have `.class` files ready to run.

## Run the Server (Normal Mode)

Start the server in Normal Mode (no artificial ACK loss). Replace `20333` with any free port in the
range 1025–65535.

```powershell
java Server 20333 nm
```

Expected server output (excerpt):

- `SERVER: Ready to receive meta data from the client`
- After the client sends metadata the server will print a META line describing total segments,
	output file name and patch size, then `SERVER: Waiting For The Actual Readings ..`.

## Run the Client (Normal Mode)

In another PowerShell window run the client. Example: send `data.csv` and ask the server to write
`output.txt` with patch size `2`:

```powershell
java Client 127.0.0.1 20333 data.csv output.txt 2 nm
```

The client prints Title Case status messages (Meta, Send: DATA, Receive: ACK) describing progress.

## Run The Client (Timeout Mode — Simulate Lost ACKs From The Network)

The client supports a timeout mode that will retransmit when an ACK is not received. To test only the
client retransmission behaviour, start the client in `wt` mode while the server is *not* running:

```powershell
java Client 127.0.0.1 20333 data.csv output.txt 2 wt
```

You should see `CLIENT: TIMEOUT ALERT` and retransmission lines until the client reaches its retry
limit (defined in `Protocol.java`) and exits.

## Run The Server (Lost Ack Mode)

To test the server handling of lost ACKs and duplicate packets, start the server in `wl` (lost ACK)
mode. The server will prompt for a loss probability (0.0–1.0). Example:

```powershell
java Server 20333 wl
# then enter e.g. 0.4 when prompted
```

Start the client in timeout mode (`wt`) so it will retransmit when ACKs are lost. The server will
occasionally simulate ACK loss according to the probability you entered and will detect duplicate
Data segments, resend ACKs for the last correctly received segment, and finally write the output file.

## What Was Implemented In `Protocol.java`

The following methods in `Protocol.java` have been implemented for this workspace (they are the
methods the assignment asks you to complete):

- `sendMetadata()` — counts lines in the CSV, stores `fileTotalReadings`, builds and sends a Meta
	segment (seqNum 0) with payload `"<fileTotalReadings>,<outputFileName>,<patchSize>"`.
- `readAndSend()` — reads up to the patch size from the CSV, composes the payload using
	`Reading.toString()` joined by `;`, creates a Data `Segment` (first Data `seqNum` = 1), sends it, and
	updates `totalSegments`.
- `receiveAck()` — receives an ACK, validates the sequence number, updates `sentReadings` when a
	correct ACK is received, prints status and exits when the transfer completes.
- `startTimeoutWithRetransmission()` — sets a socket timeout, uses `receiveAck()` to wait for ACKs,
	retransmits the same Data `Segment` on timeout, increments `currRetry` and `totalSegments` on
	retransmission, and exits after the configured maximum retries.
- `receiveWithAckLoss()` — server-side routine that simulates ACK loss (using the provided
	`isLost(prob)` helper), detects duplicate Data segments and resends the last ACK, collects
	statistics (total bytes vs useful bytes) and writes the output file when complete.

All added print messages and comments in `Protocol.java` use Title Case as requested. Local
variables introduced follow camelCase.

## Expected Output Examples

Appendices in the coursework describe the expected outputs. Briefly:

- Meta exchange: client prints `CLIENT: META [SEQ#0] (...)` and server prints `SERVER: META [SEQ#0] (...)`.
- Normal transfer: client prints `CLIENT: Send: DATA [SEQ#...]` and `CLIENT: RECEIVE: ACK [SEQ#...]` for each
	segment; server prints `SERVER: Receive: DATA [SEQ#...]` and `SERVER: Send: ACK [SEQ#...]`.
- Lost-ACK runs: server prints when it simulates an ACK loss and when duplicates are detected; at the
	end it prints `Total Bytes`, `Useful Bytes` and `Efficiency`.

## Troubleshooting

- Use a free port (1025–65535). If you get `Address already in use`, pick another port.
- If client or server fails with `ClassNotFoundException` on deserialization, recompile all files
	(`javac *.java`) and retry.
- For local testing use `127.0.0.1` as the server address so both processes run on the same machine.

## Submission

Per the assignment instructions, submit only your updated `Protocol.java` file to the course
submission system (NESS). Do not submit other files.

---

If you want, I can also run a local compilation check here and fix any compile-time errors, or
generate a short script that automates the common test runs above.
