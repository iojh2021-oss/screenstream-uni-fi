// Minimal signaling server for ScreenStream.
// It does NOT touch video/audio data — it only relays small JSON messages
// (WebRTC offer/answer/ICE candidates + control commands) between exactly
// two peers that share the same "room" code: one "phone" and one "viewer".

const http = require('http');
const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;

// rooms: Map<roomCode, { phone: ws|null, viewer: ws|null }>
const rooms = new Map();

function getRoom(code) {
  if (!rooms.has(code)) {
    rooms.set(code, { phone: null, viewer: null });
  }
  return rooms.get(code);
}

function cleanupSocket(ws) {
  if (!ws.roomCode || !ws.role) return;
  const room = rooms.get(ws.roomCode);
  if (!room) return;
  if (room[ws.role] === ws) {
    room[ws.role] = null;
  }
  // Notify the other peer that this side disconnected
  const other = ws.role === 'phone' ? room.viewer : room.phone;
  if (other && other.readyState === WebSocket.OPEN) {
    other.send(JSON.stringify({ type: 'peer-left' }));
  }
  if (!room.phone && !room.viewer) {
    rooms.delete(ws.roomCode);
  }
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('ScreenStream signaling server is running.\n');
});

const wss = new WebSocket.Server({ server });

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      return; // ignore malformed messages
    }

    // First message from a client must be a "join" message:
    // { type: "join", room: "ABC123", role: "phone" | "viewer" }
    if (msg.type === 'join') {
      const { room, role } = msg;
      if (!room || (role !== 'phone' && role !== 'viewer')) {
        ws.send(JSON.stringify({ type: 'error', message: 'invalid join message' }));
        return;
      }
      const roomObj = getRoom(room);
      if (roomObj[role]) {
        ws.send(JSON.stringify({ type: 'error', message: `a ${role} is already connected to this room` }));
        return;
      }
      roomObj[role] = ws;
      ws.roomCode = room;
      ws.role = role;
      ws.send(JSON.stringify({ type: 'joined', room, role }));

      const other = role === 'phone' ? roomObj.viewer : roomObj.phone;
      if (other && other.readyState === WebSocket.OPEN) {
        other.send(JSON.stringify({ type: 'peer-joined' }));
        ws.send(JSON.stringify({ type: 'peer-joined' }));
      }
      return;
    }

    // Any other message type (offer / answer / ice-candidate / control)
    // just gets relayed verbatim to the other peer in the same room.
    if (!ws.roomCode || !ws.role) return;
    const room = rooms.get(ws.roomCode);
    if (!room) return;
    const target = ws.role === 'phone' ? room.viewer : room.phone;
    if (target && target.readyState === WebSocket.OPEN) {
      target.send(JSON.stringify(msg));
    }
  });

  ws.on('close', () => cleanupSocket(ws));
  ws.on('error', () => cleanupSocket(ws));
});

// Keep connections alive / drop dead ones (useful on free hosting tiers)
const interval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on('close', () => clearInterval(interval));

server.listen(PORT, () => {
  console.log(`Signaling server listening on port ${PORT}`);
});
