from locust import HttpUser, task, between
import re
import random

VIDEO_IDS = [
    "ba266afe-78cd-4176-b41f-184397b4cf50",
    "57827afa-9788-4988-8030-c27728c70ee9",
    "cf1e9ba5-c66d-48a7-a081-a95efb439dcb",
    "504fa2ee-bb69-4756-b334-58cd830b940f",
]

class VODUser(HttpUser):
    wait_time = between(1, 3)

    @task
    def watch_vod_stream(self):
        video_id = random.choice(VIDEO_IDS)
        playlist_url = f"/stream/{video_id}/playlist.m3u8"

        # ✅ CORRECTED: This entire block is now indented to be inside the task
        with self.client.get(
            playlist_url,
            catch_response=True, # This allows the 'with' block to work
            name="/stream/[id]/playlist.m3u8"
        ) as response:
            if response.status_code == 200:
                ts_files = re.findall(r"^(playlist\d+\.ts)$", response.text, re.MULTILINE)
                for ts_file in ts_files:
                    chunk_url = f"/stream/{video_id}/{ts_file}"
                    self.client.get(chunk_url, name="/stream/[id]/[segment].ts")
            else:
                # It's good practice to mark failures
                response.failure(f"Failed to get playlist, status code: {response.status_code}")