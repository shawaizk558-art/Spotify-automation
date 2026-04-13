import { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import { Play, Heart, Search, Clock, Monitor, ChevronRight, Zap, Music, Calendar, ThumbsUp, UserPlus, CheckCircle2, X, Plus } from 'lucide-react';

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8000';

/** FastAPI HTTPException → { detail: string | array } */
function formatApiError(error, fallback) {
  const d = error?.response?.data?.detail;
  if (typeof d === 'string') return d;
  if (Array.isArray(d) && d.length) {
    const parts = d.map((x) => (typeof x?.msg === 'string' ? x.msg : JSON.stringify(x)));
    return parts.join('; ');
  }
  if (d != null && typeof d === 'object') return JSON.stringify(d);
  return fallback || error?.message || 'Request failed';
}

function Dashboard({ token }) {
  const [devices, setDevices] = useState([]);
  const [sessions, setSessions] = useState([]);
  const [devicesError, setDevicesError] = useState('');
  const [message, setMessage] = useState('');
  const [isCreating, setIsCreating] = useState(false);
  const [isMutating, setIsMutating] = useState(false);
  const [duration, setDuration] = useState(30);
  const [deviceId, setDeviceId] = useState('');
  const [startTime, setStartTime] = useState('');

  // Play Section
  const [playEnabled, setPlayEnabled] = useState(true);
  const [playType, setPlayType] = useState('artist');
  const [playQuery, setPlayQuery] = useState('');

  // Interact Section — Based on play query
  const [likeEnabled, setLikeEnabled] = useState(false);
  const [likeCount, setLikeCount] = useState(5);
  const [followEnabled, setFollowEnabled] = useState(false);
  const [skipEnabled, setSkipEnabled] = useState(false);
  const [skipCount, setSkipCount] = useState(3);
  const isSongPlayType = playType === 'song';

  const fetchDevices = useCallback(async () => {
    try {
      setDevicesError('');
      const response = await axios.get(`${API_URL}/devices`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      setDevices(response.data);
    } catch (error) {
      console.error('Failed to fetch devices', error);
      setDevicesError(formatApiError(error, 'Could not load devices (check API URL & CORS).'));
    }
  }, [token]);

  const fetchSessions = useCallback(async () => {
    try {
      const response = await axios.get(`${API_URL}/sessions`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      setSessions(response.data);
    } catch (error) {
      console.error('Failed to fetch sessions', error);
    }
  }, [token]);

  useEffect(() => {
    if (!token) return;
    fetchDevices();
    fetchSessions();
    const interval = setInterval(() => {
      fetchDevices();
      fetchSessions();
    }, 4000);
    return () => clearInterval(interval);
  }, [token, fetchDevices, fetchSessions]);

  useEffect(() => {
    if (isSongPlayType) {
      // Song mode: keep interaction minimal (only like).
      setFollowEnabled(false);
      setSkipEnabled(false);
      setLikeCount(1);
    }
  }, [isSongPlayType]);

  const createSession = async (e) => {
    e.preventDefault();
    setIsCreating(true);
    try {
      const dm = parseInt(String(duration), 10);
      const payload = {
        duration_minutes: Number.isFinite(dm) && dm > 0 ? dm : 30,
        device_id: deviceId,
        start_time: startTime ? new Date(startTime).toISOString() : null,
        play_enabled: playEnabled,
      };

      if (playEnabled) {
        payload.play_type = playType;
        payload.play_query = playQuery;
      }

      // Interact actions — based on play query
      const interactActions = [];
      
      if (likeEnabled && playQuery) {
        interactActions.push({
          type: 'like',
          query: playQuery,
          count: isSongPlayType ? 1 : likeCount
        });
      }
      
      if (!isSongPlayType && followEnabled && playQuery) {
        interactActions.push({
          type: 'follow',
          query: playQuery,
          count: 1
        });
      }

      if (!isSongPlayType && skipEnabled && playEnabled && playQuery) {
        const sc = parseInt(String(skipCount), 10);
        interactActions.push({
          type: 'skip',
          query: '',
          count: Number.isFinite(sc) && sc > 0 ? Math.min(sc, 50) : 3
        });
      }

      payload.interact_actions = interactActions;

      await axios.post(`${API_URL}/sessions`, payload, {
        headers: { Authorization: `Bearer ${token}` }
      });
      setMessage('✅ Session created successfully!');
      fetchSessions();
      
      // Reset form
      setPlayQuery('');
      setLikeEnabled(false);
      setLikeCount(5);
      setFollowEnabled(false);
      setSkipEnabled(false);
      setSkipCount(3);
      setDeviceId('');
      setStartTime('');
      setDuration(30);
      
      setTimeout(() => setMessage(''), 3000);
    } catch (error) {
      setMessage(`❌ ${formatApiError(error, 'Failed to create session')}`);
    } finally {
      setIsCreating(false);
    }
  };

  const startSession = async (sessionId) => {
    try {
      setIsMutating(true);
      setMessage('▶️ Starting session...');
      await axios.post(`${API_URL}/sessions/${sessionId}/start`, {}, {
        headers: { Authorization: `Bearer ${token}` }
      });
      setMessage('✅ Session started!');
      fetchSessions();
      setTimeout(() => setMessage(''), 3000);
    } catch (error) {
      console.error('Failed to start session', error);
      setMessage(`❌ ${formatApiError(error, 'Failed to start session')}`);
      setTimeout(() => setMessage(''), 8000);
    } finally {
      setIsMutating(false);
    }
  };

  const stopSession = async (sessionId) => {
    setIsMutating(true);
    try {
      setMessage('⏳ Stopping session...');
      await axios.post(`${API_URL}/sessions/${sessionId}/stop`, {}, {
        headers: { Authorization: `Bearer ${token}` }
      });
      setMessage('✅ Stop requested!');
      // Device PROGRESS aane mein thoda delay hota hai; backend "stopped" set
      // hone ke baad 1-2 baar refresh kar dete hain.
      fetchSessions();
      setTimeout(() => fetchSessions(), 1500);
      setTimeout(() => fetchSessions(), 3500);
      setTimeout(() => setMessage(''), 3000);
    } catch (error) {
      console.error('Failed to stop session', error);
      setMessage(`❌ ${formatApiError(error, 'Failed to stop session')}`);
      setTimeout(() => setMessage(''), 8000);
    } finally {
      setIsMutating(false);
    }
  };

  const deleteSession = async (sessionId) => {
    setIsMutating(true);
    try {
      if (!window.confirm('Delete this session from DB?')) return;
      setMessage('⏳ Deleting session...');
      await axios.delete(`${API_URL}/sessions/${sessionId}`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      setMessage('✅ Session deleted!');
      fetchSessions();
      setTimeout(() => setMessage(''), 3000);
    } catch (error) {
      console.error('Failed to delete session', error);
      setMessage(`❌ ${formatApiError(error, 'Failed to delete session')}`);
      setTimeout(() => setMessage(''), 8000);
    } finally {
      setIsMutating(false);
    }
  };

  const getStatusColor = (status) => {
    switch(status) {
      case 'running': return 'bg-green-500/20 text-green-400';
      case 'stopping': return 'bg-yellow-500/20 text-yellow-400';
      case 'stopped': return 'bg-yellow-500/20 text-yellow-300';
      case 'scheduled': return 'bg-cyan-500/20 text-cyan-300';
      case 'completed': return 'bg-blue-500/20 text-blue-400';
      case 'failed': return 'bg-red-500/20 text-red-400';
      default: return 'bg-yellow-500/20 text-black';
    }
  };

  const formatDateTime = (dateStr) => {
    if (!dateStr) return '';
    // Backend usually returns naive datetime strings (no timezone).
    // Treat them as UTC so UI doesn't shift by local timezone offset.
    const iso = /Z|[+-]\d\d:\d\d$/.test(dateStr) ? dateStr : `${dateStr}Z`;
    const date = new Date(iso);
    return date.toLocaleString();
  };

  const isSessionDue = (session) => {
    if (!session || !session.start_time) return false;
    const iso = /Z|[+-]\d\d:\d\d$/.test(session.start_time) ? session.start_time : `${session.start_time}Z`;
    const t = new Date(iso).getTime();
    return !Number.isNaN(t) && t <= Date.now();
  };

  const toLocalDatetimeValue = (d) => {
    // datetime-local expects: YYYY-MM-DDTHH:mm (local time, no timezone)
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-900 via-purple-900 to-gray-900">
      {/* Navbar */}
      <nav className="bg-black/30 backdrop-blur-lg border-b border-white/10 sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex justify-between items-center">
            <div className="flex items-center space-x-3">
              <div className="p-2 bg-gradient-to-r from-green-500 to-green-600 rounded-xl">
                <Zap className="w-6 h-6 text-white" />
              </div>
              <h1 className="text-2xl font-bold bg-gradient-to-r from-green-400 to-purple-400 bg-clip-text text-transparent">
                Spotify Automation
              </h1>
            </div>
            <div className="flex items-center space-x-4">
              <span className="text-gray-300 text-sm">Welcome, Admin</span>
              <button 
                onClick={() => window.location.reload()}
                className="px-4 py-2 bg-red-500/20 text-red-400 rounded-lg hover:bg-red-500/30 transition"
              >
                Logout
              </button>
            </div>
          </div>
        </div>
      </nav>

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="mb-12 text-center">
          <h2 className="text-5xl font-bold text-white mb-4">
            Automate Your <span className="bg-gradient-to-r from-green-400 to-purple-400 bg-clip-text text-transparent">Spotify</span> Experience
          </h2>
          <p className="text-gray-300 text-lg">Create automation sessions — play music and interact with the same content</p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          {/* Create Session Card */}
          <div className="lg:col-span-1">
            <div className="bg-white/5 backdrop-blur-xl rounded-2xl p-6 border border-white/10 shadow-2xl">
              <div className="flex items-center space-x-3 mb-6">
                <div className="p-3 bg-gradient-to-r from-green-500 to-green-600 rounded-xl">
                  <Plus className="w-5 h-5 text-white" />
                </div>
                <h3 className="text-xl font-semibold text-white">New Session</h3>
              </div>

              <form onSubmit={createSession} className="space-y-5">
                {/* ========== PLAY SECTION ========== */}
                <div className="bg-white/5 rounded-xl p-4 border border-white/10">
                  <div className="flex items-center justify-between mb-3">
                    <label className="flex items-center gap-2 text-gray-300 font-medium">
                      <Music className="w-4 h-4 text-green-400" />
                      <span>Play Music</span>
                    </label>
                    <label className="relative inline-flex items-center cursor-pointer">
                      <input
                        type="checkbox"
                        checked={playEnabled}
                        onChange={(e) => setPlayEnabled(e.target.checked)}
                        className="sr-only peer"
                      />
                      <div className="w-9 h-5 bg-gray-600 rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-green-500 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all"></div>
                    </label>
                  </div>

                  {playEnabled && (
                    <div className="space-y-3">
                      <select
                        value={playType}
                        onChange={(e) => setPlayType(e.target.value)}
                        className="w-full p-2.5 bg-black/50 border border-gray-600 rounded-lg text-white focus:border-green-500 focus:ring-1 focus:ring-green-500 transition"
                      >
                        <option value="artist">🎤 Play Artist</option>
                        <option value="song">🎵 Play Song</option>
                        <option value="playlist">📀 Play Playlist</option>
                      </select>
                      <input
                        type="text"
                        placeholder={playType === 'artist' ? 'Artist name (e.g., Daft Punk)' : playType === 'song' ? 'Song name (e.g., Get Lucky)' : 'Playlist name (e.g., Rock Classics)'}
                        value={playQuery}
                        onChange={(e) => setPlayQuery(e.target.value)}
                        className="w-full p-2.5 bg-black/50 border border-gray-600 rounded-lg text-white placeholder-gray-400 focus:border-green-500 focus:ring-1 focus:ring-green-500 transition"
                        required={playEnabled}
                      />
                    </div>
                  )}
                </div>

                {/* ========== INTERACT SECTION — Based on Play Query ========== */}
                {playEnabled && playQuery && (
                  <div className="bg-white/5 rounded-xl p-4 border border-white/10">
                    <div className="flex items-center gap-2 mb-3">
                      <Heart className="w-4 h-4 text-red-400" />
                      <label className="text-gray-300 font-medium">Interact with "{playQuery}"</label>
                    </div>
                    
                    <div className="space-y-3">
                      {/* Like Option */}
                      <div className="flex items-center justify-between p-3 bg-black/30 rounded-lg">
                        <div className="flex items-center gap-3">
                          <Heart className="w-4 h-4 text-red-400" />
                          <span className="text-gray-300">{isSongPlayType ? 'Like song' : 'Like songs from this'}</span>
                          {!isSongPlayType && (
                            <span className="text-green-400 font-medium">"{playQuery}"</span>
                          )}
                        </div>
                        <div className="flex items-center gap-3">
                          {!isSongPlayType && likeEnabled && (
                            <input
                              type="number"
                              min="1"
                              max="50"
                              value={likeCount}
                              onChange={(e) => setLikeCount(parseInt(e.target.value) || 1)}
                              className="w-16 p-1.5 bg-black/50 border border-gray-600 rounded-lg text-white text-center text-sm"
                            />
                          )}
                          {isSongPlayType ? (
                            <button
                              type="button"
                              onClick={() => setLikeEnabled((v) => !v)}
                              className={`px-3 py-1 rounded-lg text-sm transition ${
                                likeEnabled
                                  ? 'bg-green-500/20 text-green-300 border border-green-500/40'
                                  : 'bg-white/10 text-gray-200 border border-white/20 hover:bg-white/20'
                              }`}
                            >
                              {likeEnabled ? 'OK' : 'Set OK'}
                            </button>
                          ) : (
                            <label className="relative inline-flex items-center cursor-pointer">
                              <input
                                type="checkbox"
                                checked={likeEnabled}
                                onChange={(e) => setLikeEnabled(e.target.checked)}
                                className="sr-only peer"
                              />
                              <div className="w-9 h-5 bg-gray-600 rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-red-500 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all"></div>
                            </label>
                          )}
                        </div>
                      </div>

                      {!isSongPlayType && (
                        <>
                          {/* Follow Option */}
                          <div className="flex items-center justify-between p-3 bg-black/30 rounded-lg">
                            <div className="flex items-center gap-3">
                              <UserPlus className="w-4 h-4 text-purple-400" />
                              <span className="text-gray-300">Follow this</span>
                              <span className="text-green-400 font-medium">"{playQuery}"</span>
                            </div>
                            <label className="relative inline-flex items-center cursor-pointer">
                              <input
                                type="checkbox"
                                checked={followEnabled}
                                onChange={(e) => setFollowEnabled(e.target.checked)}
                                className="sr-only peer"
                              />
                              <div className="w-9 h-5 bg-gray-600 rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-purple-500 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all"></div>
                            </label>
                          </div>

                          {/* Skip (next track) */}
                          <div className="flex items-center justify-between p-3 bg-black/30 rounded-lg">
                            <div className="flex items-center gap-3">
                              <ChevronRight className="w-4 h-4 text-amber-400" />
                              <span className="text-gray-300">Skip to next track</span>
                            </div>
                            <div className="flex items-center gap-3">
                              {skipEnabled && (
                                <input
                                  type="number"
                                  min="1"
                                  max="50"
                                  value={skipCount}
                                  onChange={(e) => setSkipCount(parseInt(e.target.value) || 1)}
                                  className="w-16 p-1.5 bg-black/50 border border-gray-600 rounded-lg text-white text-center text-sm"
                                />
                              )}
                              <label className="relative inline-flex items-center cursor-pointer">
                                <input
                                  type="checkbox"
                                  checked={skipEnabled}
                                  onChange={(e) => setSkipEnabled(e.target.checked)}
                                  className="sr-only peer"
                                />
                                <div className="w-9 h-5 bg-gray-600 rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-amber-500 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all"></div>
                              </label>
                            </div>
                          </div>
                        </>
                      )}
                    </div>
                    
                    <p className="text-gray-500 text-xs mt-3">
                      {likeEnabled && `❤️ Like${isSongPlayType ? '' : ` ×${likeCount}`} on current track`}
                      {!isSongPlayType && likeEnabled && followEnabled && ' • '}
                      {!isSongPlayType && followEnabled && `👤 Follow "${playQuery}"`}
                      {!isSongPlayType && (likeEnabled || followEnabled) && skipEnabled && ' • '}
                      {!isSongPlayType && skipEnabled && `⏭ Skip ×${skipCount}`}
                      {!likeEnabled && !followEnabled && !skipEnabled && 'No interaction selected'}
                    </p>
                  </div>
                )}

                {/* ========== SCHEDULE SECTION ========== */}
                <div className="bg-white/5 rounded-xl p-4 border border-white/10">
                  <div className="flex items-center gap-2 mb-3">
                    <Calendar className="w-4 h-4 text-blue-400" />
                    <label className="text-gray-300 font-medium">Schedule (Optional)</label>
                  </div>
                  <div className="relative">
                    <Clock className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
                    <input
                      type="datetime-local"
                      value={startTime}
                      onChange={(e) => setStartTime(e.target.value)}
                      className="w-full pl-10 pr-4 py-2.5 bg-black/50 border border-gray-600 rounded-lg text-white focus:border-green-500 focus:ring-1 focus:ring-green-500 transition"
                    />
                  </div>
                  <div className="flex gap-2 mt-2">
                    <button
                      type="button"
                      onClick={() => {
                        const now = new Date();
                        now.setMinutes(now.getMinutes() + 30);
                        setStartTime(toLocalDatetimeValue(now));
                      }}
                      className="text-xs text-gray-400 hover:text-green-400 transition"
                    >
                      +30 min
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        const now = new Date();
                        now.setHours(now.getHours() + 1);
                        setStartTime(toLocalDatetimeValue(now));
                      }}
                      className="text-xs text-gray-400 hover:text-green-400 transition"
                    >
                      +1 hour
                    </button>
                    <button
                      type="button"
                      onClick={() => setStartTime('')}
                      className="text-xs text-gray-400 hover:text-red-400 transition"
                    >
                      Clear
                    </button>
                  </div>
                  <p className="text-gray-500 text-xs mt-2">Leave empty to start manually</p>
                  <p className="text-yellow-500/90 text-xs mt-2">
                    Auto-run needs: backend server running at this time, phone app open with device <strong>online</strong>,
                    and correct date in the picker (time is your device local → stored as UTC).
                  </p>
                </div>

                {/* Duration */}
                <div className="bg-white/5 rounded-xl p-4 border border-white/10">
                  <label className="flex items-center gap-2 text-gray-300 font-medium mb-2">
                    <Clock className="w-4 h-4 text-blue-400" />
                    <span>Duration</span>
                  </label>
                  <div className="flex items-center gap-2">
                    <input
                      type="number"
                      value={duration}
                      onChange={(e) => setDuration(e.target.value)}
                      className="w-24 p-2.5 bg-black/50 border border-gray-600 rounded-lg text-white text-center focus:border-green-500"
                    />
                    <span className="text-gray-400">minutes</span>
                  </div>
                </div>

                {/* Device Select */}
                <div className="bg-white/5 rounded-xl p-4 border border-white/10">
                  <label className="flex items-center gap-2 text-gray-300 font-medium mb-2">
                    <Monitor className="w-4 h-4 text-purple-400" />
                    <span>Device</span>
                  </label>
                  <select
                    value={deviceId}
                    onChange={(e) => setDeviceId(e.target.value)}
                    className="w-full p-2.5 bg-black/50 border border-gray-600 rounded-lg text-white focus:border-green-500"
                    required
                  >
                    <option value="" disabled>Select a device</option>
                    {devices.length === 0 ? (
                      <option value="" disabled>⚠️ No devices connected — open Android app</option>
                    ) : (
                      devices.map(device => (
                        <option key={device.device_id} value={device.device_id}>
                          {device.name} ({device.status})
                        </option>
                      ))
                    )}
                  </select>
                  {devices.length === 0 && (
                    <p className="text-yellow-400 text-xs mt-2">
                      📱 No devices found — make sure Android app is running
                    </p>
                  )}
                </div>

                <button
                  type="submit"
                  disabled={isCreating || !deviceId || (playEnabled && !playQuery)}
                  className="w-full py-3 bg-gradient-to-r from-green-500 to-green-600 text-white font-semibold rounded-xl hover:from-green-600 hover:to-green-700 transition transform hover:scale-[1.02] disabled:opacity-50 disabled:hover:scale-100"
                >
                  {isCreating ? 'Creating...' : '✨ Create Session'}
                </button>

                {message && (
                  <div className="p-3 bg-green-500/20 border border-green-500/50 rounded-lg animate-pulse">
                    <p className="text-green-400 text-sm text-center">{message}</p>
                  </div>
                )}
              </form>
            </div>
          </div>

          {/* Right Column — Devices & Sessions */}
          <div className="lg:col-span-2 space-y-8">
            {/* Devices Section */}
            <div className="bg-white/5 backdrop-blur-xl rounded-2xl p-6 border border-white/10">
              <div className="flex items-center justify-between mb-6">
                <div className="flex items-center space-x-3">
                  <div className="p-2 bg-purple-500/20 rounded-lg">
                    <Monitor className="w-5 h-5 text-purple-400" />
                  </div>
                  <h3 className="text-xl font-semibold text-white">Connected Devices</h3>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => { fetchDevices(); fetchSessions(); }}
                    className="px-2 py-1 text-xs rounded-lg bg-white/10 text-gray-200 hover:bg-white/20"
                  >
                    Refresh
                  </button>
                  <span className="px-3 py-1 bg-white/10 rounded-full text-sm text-gray-300">{devices.length} device(s)</span>
                </div>
              </div>

              {devicesError && (
                <div className="mb-4 p-3 rounded-lg bg-red-500/15 border border-red-500/40 text-red-300 text-sm">
                  {devicesError}
                  <p className="text-red-400/80 text-xs mt-2">API: {API_URL} — open DevTools → Network if this persists.</p>
                </div>
              )}

              {devices.length === 0 ? (
                <div className="text-center py-8">
                  <Monitor className="w-12 h-12 text-gray-500 mx-auto mb-3" />
                  <p className="text-gray-400">No devices in the list yet</p>
                  <p className="text-gray-500 text-sm mt-1">Open the Android bot app (same Wi‑Fi). List refreshes every few seconds.</p>
                  <p className="text-gray-600 text-xs mt-2">Phone can show &quot;connected&quot; while this page still fails — fix CORS / VITE_API_URL, then Refresh.</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {devices.map(device => (
                    <div key={device.id} className="flex items-center justify-between p-4 bg-black/30 rounded-xl border border-white/10 hover:border-green-500/50 transition">
                      <div className="flex items-center space-x-3">
                        <div className={`w-2.5 h-2.5 rounded-full ${device.status === 'online' ? 'bg-green-500 animate-pulse' : 'bg-red-500'}`} />
                        <div>
                          <p className="text-white font-medium">{device.name || device.device_id}</p>
                          <p className="text-gray-400 text-sm">{device.device_id}</p>
                        </div>
                      </div>
                      <span className={`px-3 py-1 rounded-full text-xs font-medium ${
                        device.status === 'online' ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'
                      }`}>
                        {device.status || 'offline'}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Sessions Section */}
            <div className="bg-white/5 backdrop-blur-xl rounded-2xl p-6 border border-white/10">
              <div className="flex items-center justify-between mb-6">
                <div className="flex items-center space-x-3">
                  <div className="p-2 bg-blue-500/20 rounded-lg">
                    <Clock className="w-5 h-5 text-blue-400" />
                  </div>
                  <h3 className="text-xl font-semibold text-white">Sessions</h3>
                </div>
                <span className="px-3 py-1 bg-white/10 rounded-full text-sm text-gray-300">{sessions.length} session(s)</span>
              </div>

              {sessions.length === 0 ? (
                <div className="text-center py-8">
                  <Play className="w-12 h-12 text-gray-500 mx-auto mb-3" />
                  <p className="text-gray-400">No sessions created yet</p>
                  <p className="text-gray-500 text-sm mt-1">Create your first session above</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {sessions.map(session => (
                    <div key={session.id} className="group p-4 bg-black/30 rounded-xl border border-white/10 hover:border-green-500/50 transition">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center space-x-3 flex-1">
                          <div className="p-2 bg-green-500/20 rounded-lg">
                            <Music className="w-4 h-4 text-green-400" />
                          </div>
                          <div className="flex-1">
                            <p className="text-white font-medium">
                              {session.play_query || 'Auto Session'}
                            </p>
                            <div className="flex items-center flex-wrap gap-3 mt-1">
                              <span className="text-gray-400 text-sm flex items-center">
                                <Clock className="w-3 h-3 mr-1" />
                                {session.duration_minutes} min
                              </span>
                              <span className="text-gray-400 text-sm flex items-center">
                                <Monitor className="w-3 h-3 mr-1" />
                                {session.device_id}
                              </span>
                              {session.start_time && (
                                <span className="text-blue-400 text-xs bg-blue-500/20 px-2 py-0.5 rounded flex items-center">
                                  <Calendar className="w-3 h-3 mr-1" />
                                  Start {formatDateTime(session.start_time)}
                                </span>
                              )}
                              {session.end_time && (
                                <span className="text-amber-400 text-xs bg-amber-500/20 px-2 py-0.5 rounded flex items-center">
                                  <Clock className="w-3 h-3 mr-1" />
                                  End {formatDateTime(session.end_time)}
                                </span>
                              )}
                            </div>
                            {session.interact_actions && session.interact_actions.length > 0 && (
                              <div className="flex flex-wrap gap-2 mt-2">
                                {session.interact_actions.map((action, idx) => (
                                  <span key={idx} className="text-xs px-2 py-0.5 rounded bg-red-500/20 text-red-400">
                                    {action.type === 'like' && `❤️ Like ×${action.count}${action.query ? ` (${action.query})` : ''}`}
                                    {action.type === 'follow' && `👤 Follow "${action.query || '?'}"`}
                                    {action.type === 'skip' && `⏭ Skip ×${action.count}`}
                                    {!['like', 'follow', 'skip'].includes(action.type) && `${action.type} ×${action.count}`}
                                  </span>
                                ))}
                              </div>
                            )}
                          </div>
                          <div className="flex items-center space-x-3">
                            <span className={`px-3 py-1 rounded-full text-xs font-medium ${getStatusColor(session.status)}`}>
                              {session.status || 'pending'}
                            </span>
                            {(session.status === 'pending' ||
                              (session.status === 'scheduled' && isSessionDue(session))) && (
                              <button
                                onClick={() => startSession(session.id)}
                                disabled={isMutating}
                                className="px-3 py-1 bg-green-500/20 text-green-400 rounded-lg text-sm hover:bg-green-500/30 transition disabled:opacity-50"
                              >
                                Start
                              </button>
                            )}
                            {(session.status === 'pending' || session.status === 'scheduled') && (
                              <button
                                type="button"
                                onClick={() => stopSession(session.id)}
                                disabled={isMutating}
                                className="px-3 py-1 bg-orange-500/20 text-orange-300 rounded-lg text-sm hover:bg-orange-500/30 transition disabled:opacity-50"
                              >
                                Cancel
                              </button>
                            )}
                            {session.status === 'running' && (
                              <button
                                onClick={() => stopSession(session.id)}
                                disabled={isMutating}
                                className="px-3 py-1 bg-red-500/20 text-red-400 rounded-lg text-sm hover:bg-red-500/30 transition disabled:opacity-50"
                              >
                                Stop
                              </button>
                            )}
                            {session.status === 'stopping' && (
                              <button
                                disabled
                                className="px-3 py-1 bg-yellow-500/20 text-yellow-300 rounded-lg text-sm cursor-not-allowed"
                              >
                                Stopping...
                              </button>
                            )}
                            {(session.status === 'pending' ||
                              session.status === 'scheduled' ||
                              session.status === 'stopping' ||
                              session.status === 'stopped' ||
                              session.status === 'completed' ||
                              session.status === 'failed') && (
                              <button
                                onClick={() => deleteSession(session.id)}
                                disabled={isMutating}
                                className="px-3 py-1 bg-white/10 text-gray-200 rounded-lg text-sm hover:bg-white/20 transition disabled:opacity-50"
                              >
                                Delete
                              </button>
                            )}
                            <ChevronRight className="w-5 h-5 text-gray-500 group-hover:text-green-400 transition" />
                          </div>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default Dashboard;