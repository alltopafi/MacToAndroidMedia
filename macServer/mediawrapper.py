import asyncio
import time
from typing import Optional
from contextlib import asynccontextmanager
from fastapi import FastAPI, Response, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import uvicorn
import base64

class MediaState(BaseModel):
    title: Optional[str] = None
    artist: Optional[str] = None
    album: Optional[str] = None
    is_playing: bool = False
    is_active: bool = False
    playback_rate: float = 0.0
    elapsed_time: Optional[float] = None
    duration: Optional[float] = None
    duration_formatted: Optional[str] = None
    elapsed_formatted: Optional[str] = None
    artwork: Optional[str] = None

class MediaTracker:
    """Asynchronous tracker for macOS media state via nowplaying-cli."""
    
    async def execute_command(self, command: str):
        """Executes a playback control command."""
        try:
            proc = await asyncio.create_subprocess_exec(
                'nowplaying-cli', command,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL
            )
            await proc.wait()
        except FileNotFoundError:
            raise RuntimeError("Error: 'nowplaying-cli' not found.")

    async def get_current_state(self) -> MediaState:
        """Fetches all required metadata in a single JSON call using get-raw."""
        try:
            proc = await asyncio.create_subprocess_exec(
                'nowplaying-cli', 'get-raw',
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL
            )
            stdout, _ = await proc.communicate()
            val = stdout.decode().strip()
            
            if not val or val == "null":
                return MediaState(is_active=False)
                
            import json
            data = json.loads(val)
            
            title = data.get('kMRMediaRemoteNowPlayingInfoTitle')
            artist = data.get('kMRMediaRemoteNowPlayingInfoArtist')
            album = data.get('kMRMediaRemoteNowPlayingInfoAlbum')
            playback_rate_raw = data.get('kMRMediaRemoteNowPlayingInfoPlaybackRate', 0)
            elapsed = data.get('kMRMediaRemoteNowPlayingInfoElapsedTime')
            duration = data.get('kMRMediaRemoteNowPlayingInfoDuration')
            artwork_b64 = data.get('kMRMediaRemoteNowPlayingInfoArtworkData')
            
            if artwork_b64 == "null":
                artwork_b64 = None
            
            def safe_float(v) -> Optional[float]:
                if v is None or str(v) == "null": return None
                try:
                    return float(v)
                except ValueError:
                    return None
                    
            pb_rate = safe_float(playback_rate_raw) or 0.0
            is_playing = pb_rate != 0.0
            is_active = title is not None or artist is not None
            
            def format_time(seconds: Optional[float]) -> Optional[str]:
                if seconds is None:
                    return None
                s = int(seconds)
                h = s // 3600
                m = (s % 3600) // 60
                sec = s % 60
                return f"{h:02d}:{m:02d}:{sec:02d}"

            elapsed_val = safe_float(elapsed)
            duration_val = safe_float(duration)
            
            return MediaState(
                title=title if title != "null" else None,
                artist=artist if artist != "null" else None,
                album=album if album != "null" else None,
                is_playing=is_playing,
                is_active=is_active,
                playback_rate=pb_rate,
                elapsed_time=elapsed_val,
                duration=duration_val,
                elapsed_formatted=format_time(elapsed_val),
                duration_formatted=format_time(duration_val),
                artwork=artwork_b64
            )
        except Exception as e:
            return MediaState(is_active=False)

tracker = MediaTracker()

class ConnectionManager:
    def __init__(self):
        self.active_connections: list[WebSocket] = []

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    async def broadcast(self, message: dict):
        for connection in self.active_connections:
            try:
                await connection.send_json(message)
            except Exception:
                pass

manager = ConnectionManager()
current_state_cache = None
last_raw_elapsed = None
last_update_time = None

async def state_broadcaster():
    global current_state_cache, last_raw_elapsed, last_update_time
    while True:
        try:
            now = time.time()
            new_state = await tracker.get_current_state()
            raw_elapsed = new_state.elapsed_time
            
            # Hybrid approach: Increment based on playback rate if CLI is stuck
            if current_state_cache and new_state.title == current_state_cache.title:
                if raw_elapsed == last_raw_elapsed:
                    # CLI is stuck
                    if new_state.is_playing and current_state_cache.elapsed_time is not None and last_update_time is not None:
                        delta = now - last_update_time
                        new_state.elapsed_time = current_state_cache.elapsed_time + (delta * new_state.playback_rate)
                        
                        # Reformat time
                        s = int(new_state.elapsed_time)
                        h = s // 3600
                        m = (s % 3600) // 60
                        sec = s % 60
                        new_state.elapsed_formatted = f"{h:02d}:{m:02d}:{sec:02d}"
                else:
                    # CLI actually updated
                    last_raw_elapsed = raw_elapsed
            else:
                last_raw_elapsed = raw_elapsed
                
            last_update_time = now

            print(f"Elapsed Time: {new_state.elapsed_formatted} (Raw: {raw_elapsed})")
            
            if new_state != current_state_cache:
                payload = new_state.model_dump()
                # Null out the artwork if the title hasn't changed to save websocket bandwidth
                if current_state_cache and new_state.title == current_state_cache.title:
                    payload['artwork'] = None
                
                current_state_cache = new_state
                await manager.broadcast(payload)
                
        except Exception as e:
            print(f"Error in broadcaster: {e}")
        await asyncio.sleep(1)

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Start the background task
    task = asyncio.create_task(state_broadcaster())
    yield
    # Clean up the background task on shutdown
    task.cancel()

app = FastAPI(title="macOS Media Tracker API", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/api/state", response_model=MediaState)
async def get_state():
    """Returns the current macOS media playback state."""
    state = await tracker.get_current_state()
    return state

@app.post("/api/control/{action}")
async def control_media(action: str):
    """Controls media playback. Valid actions: play, pause, togglePlayPause, next, previous"""
    valid_actions = ["play", "pause", "togglePlayPause", "next", "previous"]
    if action not in valid_actions:
        raise HTTPException(status_code=400, detail=f"Invalid action. Must be one of: {', '.join(valid_actions)}")
    
    await tracker.execute_command(action)
    return {"status": "success", "action": action}

@app.websocket("/api/ws")
async def websocket_endpoint(websocket: WebSocket):
    """Real-time stream of the macOS media state."""
    await manager.connect(websocket)
    try:
        # Push the latest state immediately upon connection
        if current_state_cache:
            await websocket.send_json(current_state_cache.model_dump())
            
        while True:
            # We must await receive to keep the connection alive
            # You could easily expand this to accept {"action": "play"} commands over WS!
            _ = await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket)

if __name__ == "__main__":
    print("Starting FastAPI server on http://0.0.0.0:8000")
    uvicorn.run("mediawrapper:app", host="0.0.0.0", port=8000, reload=True)
