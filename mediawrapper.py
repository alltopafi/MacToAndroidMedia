import asyncio
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
    elapsed_time: Optional[float] = None
    duration: Optional[float] = None

class MediaTracker:
    """Asynchronous tracker for macOS media state via nowplaying-cli."""
    
    async def _fetch_field(self, field: str) -> Optional[str]:
        """Runs nowplaying-cli asynchronously and gracefully handles missing data."""
        try:
            proc = await asyncio.create_subprocess_exec(
                'nowplaying-cli', 'get', field,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL
            )
            stdout, _ = await proc.communicate()
            val = stdout.decode().strip()
            return None if val == "null" or not val else val
        except FileNotFoundError:
            raise RuntimeError("Error: 'nowplaying-cli' not found. Install via Homebrew: brew install nowplaying-cli")

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
            raise RuntimeError("Error: 'nowplaying-cli' not found. Install via Homebrew: brew install nowplaying-cli")

    async def get_artwork(self) -> Optional[bytes]:
        """Fetches and decodes the current album artwork."""
        b64_data = await self._fetch_field('artworkData')
        if not b64_data or b64_data == "null":
            return None
        try:
            return base64.b64decode(b64_data)
        except Exception:
            return None

    async def get_current_state(self) -> MediaState:
        """Concurrently fetches all required metadata."""
        # Run subprocesses concurrently to minimize blocking time
        results = await asyncio.gather(
            self._fetch_field('title'),
            self._fetch_field('artist'),
            self._fetch_field('album'),
            self._fetch_field('playbackRate'),
            self._fetch_field('elapsedTime'),
            self._fetch_field('duration')
        )
        
        title, artist, album, playback_rate, elapsed, duration = results
        is_playing = playback_rate == "1"
        is_active = title is not None or artist is not None
        
        def safe_float(val: Optional[str]) -> Optional[float]:
            try:
                return float(val) if val else None
            except ValueError:
                return None
        
        return MediaState(
            title=title,
            artist=artist,
            album=album,
            is_playing=is_playing,
            is_active=is_active,
            elapsed_time=safe_float(elapsed),
            duration=safe_float(duration)
        )

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

async def state_broadcaster():
    global current_state_cache
    while True:
        try:
            new_state = await tracker.get_current_state()
            # Broadcast on any change (including elapsedTime updates every second)
            if new_state != current_state_cache:
                current_state_cache = new_state
                await manager.broadcast(new_state.model_dump())
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

@app.get("/api/artwork")
async def get_artwork():
    """Returns the current media artwork as a JPEG image."""
    img_bytes = await tracker.get_artwork()
    if not img_bytes:
        raise HTTPException(status_code=404, detail="No artwork available for the current media.")
    
    # Return directly as an image so it renders natively in the browser
    return Response(content=img_bytes, media_type="image/jpeg")

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
    print("Starting FastAPI server on http://127.0.0.1:8000")
    uvicorn.run("mediawrapper:app", host="127.0.0.1", port=8000, reload=True)
