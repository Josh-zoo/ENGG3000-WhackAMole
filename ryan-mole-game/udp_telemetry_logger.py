import socket
import time
import csv
import re

UDP_IP = "0.0.0.0" 
UDP_PORT = 4210
CSV_FILENAME = "empirical_telemetry_V2.csv"

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((UDP_IP, UDP_PORT))
print(f"Listening for UDP telemetry on port {UDP_PORT}... Press Ctrl+C to stop and save.")

expected_seq = None
total_lost = 0
total_received = 0
last_pc_time = None

with open(CSV_FILENAME, mode='w', newline='') as file:
    writer = csv.writer(file)
    # 1. Add timestamps to UDP packet reception & 3. Export data to CSV format
    writer.writerow(["PC_Timestamp", "ESP32_Timestamp_ms", "Sequence_Num", "Latency_Delta_ms", "Cumulative_Lost_Packets", "S1", "S2", "S3", "S4"])

    try:
        while True:
            data, addr = sock.recvfrom(1024)
            pc_recv_time = time.time()
            payload = data.decode('utf-8').strip()

            # Parse the new payload format
            match = re.search(r"Seq:(\d+)\|Time:(\d+)\|S1:(.*?)\|S2:(.*?)\|S3:(.*?)\|S4:(.*)", payload)
            if match:
                seq_num = int(match.group(1))
                esp_time = int(match.group(2))
                s1, s2, s3, s4 = match.group(3), match.group(4), match.group(5), match.group(6)

                # 2. Calculate packet loss rate
                if expected_seq is not None and seq_num > expected_seq:
                    lost = seq_num - expected_seq
                    total_lost += lost
                expected_seq = seq_num + 1
                total_received += 1

                # 2. Calculate latency (Measured as jitter/delta between packet arrivals)
                latency_delta_ms = 0.0
                if last_pc_time is not None:
                    latency_delta_ms = round((pc_recv_time - last_pc_time) * 1000, 2)
                last_pc_time = pc_recv_time

                # Write to CSV
                writer.writerow([pc_recv_time, esp_time, seq_num, latency_delta_ms, total_lost, s1, s2, s3, s4])
                print(f"Seq: {seq_num} | "f"S1: {s1} | "f"S2: {s2} | "f"S3: {s3} | "f"S4: {s4} | "f"Latency Delta: {latency_delta_ms} ms | "f"Total Lost: {total_lost}"
)

    except KeyboardInterrupt:
        print(f"\n--- Logging Complete ---")
        print(f"Total Received: {total_received}")
        print(f"Total Lost: {total_lost}")
        if (total_received + total_lost) > 0:
            loss_rate = (total_lost / (total_received + total_lost)) * 100
            print(f"Final Packet Loss Rate: {loss_rate:.2f}%")
        print(f"Data saved to {CSV_FILENAME}")