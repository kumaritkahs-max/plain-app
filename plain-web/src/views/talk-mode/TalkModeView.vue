<template>
  <div class="talk-page">
    <div class="page-header">
      <h2><i-lucide:headphones /> {{ $t('page_title.talk_mode') }}</h2>
      <p class="muted">{{ $t('talk_mode_desc') }}</p>
    </div>

    <div class="talk-grid">
      <!-- Device → Panel audio (reuses LiveMic) -->
      <section class="talk-card">
        <header>
          <i-lucide:mic class="ic" />
          <div>
            <h3>{{ $t('device_audio') }}</h3>
            <p class="muted">{{ $t('device_audio_desc') }}</p>
          </div>
          <div class="header-actions">
            <button class="chip-btn" :class="{ active: micMuted }" @click="toggleMicMute" :disabled="!micOn">
              <i-lucide:mic-off v-if="micMuted" /><i-lucide:mic v-else />
              <span>{{ micMuted ? $t('unmute') : $t('mute') }}</span>
            </button>
            <label class="switch">
              <input type="checkbox" :checked="micOn" @change="toggleMic(($event.target as HTMLInputElement).checked)" />
              <span class="knob"></span>
            </label>
            <span>{{ micOn ? $t('on') : $t('off') }}</span>
          </div>
        </header>
        <div v-if="micOn" class="embed">
          <LiveMicView />
        </div>
        <div v-else class="placeholder">
          <i-lucide:mic-off />
          <p>{{ $t('mic_disabled') }}</p>
        </div>
      </section>

      <!-- Camera (off by default) -->
      <section class="talk-card">
        <header>
          <i-lucide:camera class="ic" />
          <div>
            <h3>{{ $t('device_camera') }}</h3>
            <p class="muted">{{ $t('device_camera_desc') }}</p>
          </div>
          <div class="header-actions">
            <button class="chip-btn" @click="flipCamera" :disabled="!cameraOn">
              <i-lucide:refresh-ccw />
              <span>{{ $t('flip') }}</span>
            </button>
            <label class="switch">
              <input type="checkbox" :checked="cameraOn" @change="toggleCam(($event.target as HTMLInputElement).checked)" />
              <span class="knob"></span>
            </label>
            <span>{{ cameraOn ? $t('on') : $t('off') }}</span>
          </div>
        </header>
        <div v-if="cameraOn" class="embed">
          <LiveCameraView />
        </div>
        <div v-else class="placeholder">
          <i-lucide:video-off />
          <p>{{ $t('camera_disabled') }}</p>
        </div>
      </section>

      <!-- Panel → Device push-to-talk -->
      <section class="talk-card span-2">
        <header>
          <i-lucide:radio-tower class="ic" />
          <div>
            <h3>{{ $t('panel_to_device') }}</h3>
            <p class="muted">{{ $t('panel_to_device_desc') }}</p>
          </div>
          <div class="header-actions">
            <span class="badge" :class="recording ? 'badge-rec' : 'badge-idle'">
              <span class="dot" :class="{ pulse: recording }"></span>
              {{ recording ? $t('transmitting') : $t('idle') }}
            </span>
          </div>
        </header>

        <div class="ptt-row">
          <button
            class="ptt-btn"
            :class="{ active: recording }"
            @mousedown="startRec"
            @mouseup="stopRec"
            @mouseleave="stopRec"
            @touchstart.prevent="startRec"
            @touchend.prevent="stopRec"
            :disabled="sending"
          >
            <i-lucide:mic v-if="!recording" />
            <i-lucide:mic-off v-else />
            <span>{{ recording ? $t('release_to_send') : $t('hold_to_talk') }}</span>
          </button>
          <div class="ptt-info">
            <p class="muted">{{ $t('ptt_help') }}</p>
            <p v-if="lastError" class="error-text">{{ lastError }}</p>
            <p v-if="lastSent" class="muted small">
              {{ $t('last_clip') }}: {{ lastDuration.toFixed(1) }}s · {{ formatTime(lastSent) }}
            </p>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import LiveMicView from '@/views/live-monitor/LiveMicView.vue'
import LiveCameraView from '@/views/live-monitor/LiveCameraView.vue'
import { initMutation, playAudioBase64GQL, setLiveMicMutedGQL, stopLiveMicGQL, startLiveMicGQL, switchLiveCameraFacingGQL, stopLiveCameraGQL } from '@/lib/api/mutation'
import { gqlFetch } from '@/lib/api/gql-client'
import { liveMicStateGQL, liveCameraStateGQL } from '@/lib/api/query'
import toast from '@/components/toaster'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const cameraOn = ref(false)
const micOn = ref(false)
const micMuted = ref(false)
const recording = ref(false)
const sending = ref(false)
const lastSent = ref<number | null>(null)
const lastDuration = ref(0)
const lastError = ref('')

let mediaRecorder: MediaRecorder | null = null
let mediaStream: MediaStream | null = null
let chunks: Blob[] = []
let recStart = 0

const { mutate: mPlay } = initMutation({ document: playAudioBase64GQL })
const { mutate: mStartMic } = initMutation({ document: startLiveMicGQL })
const { mutate: mStopMic } = initMutation({ document: stopLiveMicGQL })
const { mutate: mMuteMic } = initMutation({ document: setLiveMicMutedGQL })
const { mutate: mFlipCam } = initMutation({ document: switchLiveCameraFacingGQL })
const { mutate: mStopCam } = initMutation({ document: stopLiveCameraGQL })

async function toggleMic(on: boolean) {
  micOn.value = on
  if (on) await mStartMic()
  else await mStopMic()
}
async function toggleMicMute() {
  micMuted.value = !micMuted.value
  await mMuteMic({ muted: micMuted.value })
}
async function toggleCam(on: boolean) {
  cameraOn.value = on
  if (!on) await mStopCam()
}
async function flipCamera() {
  await mFlipCam()
}

function formatTime(t: number) { return new Date(t).toLocaleTimeString() }

async function startRec() {
  if (recording.value || sending.value) return
  lastError.value = ''
  try {
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const mime = pickMime()
    mediaRecorder = new MediaRecorder(mediaStream, { mimeType: mime })
    chunks = []
    mediaRecorder.ondataavailable = (e) => { if (e.data?.size) chunks.push(e.data) }
    mediaRecorder.onstop = sendClip
    recStart = Date.now()
    mediaRecorder.start()
    recording.value = true
  } catch (e: any) {
    lastError.value = e?.message || String(e)
  }
}

function stopRec() {
  if (!recording.value) return
  recording.value = false
  try { mediaRecorder?.stop() } catch {}
}

function pickMime(): string {
  const candidates = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4', 'audio/webm']
  for (const m of candidates) if (MediaRecorder.isTypeSupported?.(m)) return m
  return ''
}

async function sendClip() {
  const dur = (Date.now() - recStart) / 1000
  if (dur < 0.2 || chunks.length === 0) { cleanup(); return }
  const blob = new Blob(chunks, { type: mediaRecorder?.mimeType || 'audio/webm' })
  const buf = await blob.arrayBuffer()
  const base64 = btoa(String.fromCharCode(...new Uint8Array(buf)))
  sending.value = true
  try {
    const r = await mPlay({ data: base64, mime: blob.type })
    if (r) { lastSent.value = Date.now(); lastDuration.value = dur; toast(t('sent')) }
  } catch (e: any) {
    lastError.value = e?.message || String(e)
  } finally {
    sending.value = false
    cleanup()
  }
}
function cleanup() {
  mediaStream?.getTracks().forEach((t) => t.stop())
  mediaStream = null; mediaRecorder = null; chunks = []
}

onMounted(async () => {
  try {
    const r = await gqlFetch<{ liveMicState: { running: boolean; muted: boolean } }>(liveMicStateGQL)
    if (!r.errors) { micOn.value = r.data.liveMicState.running; micMuted.value = r.data.liveMicState.muted }
  } catch {}
  try {
    const r = await gqlFetch<{ liveCameraState: { running: boolean } }>(liveCameraStateGQL)
    if (!r.errors) cameraOn.value = r.data.liveCameraState.running
  } catch {}
})

onUnmounted(() => { cleanup() })
</script>

<style scoped lang="scss">
.talk-page { padding: 16px; }
.page-header { margin-bottom: 16px; h2 { display: flex; gap: 8px; align-items: center; margin: 0 0 4px; } }
.muted { color: var(--md-sys-color-on-surface-variant); font-size: 0.875rem; }
.muted.small { font-size: 0.8125rem; }
.error-text { color: var(--md-sys-color-error); font-size: 0.875rem; margin-top: 6px; }
.talk-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 16px;
}
@media (max-width: 900px) { .talk-grid { grid-template-columns: 1fr; } .talk-card.span-2 { grid-column: span 1; } }
.talk-card.span-2 { grid-column: span 2; }
.talk-card {
  background: var(--md-sys-color-surface-container); border-radius: 16px; padding: 16px;
  display: flex; flex-direction: column; gap: 12px;
  header { display: flex; gap: 12px; align-items: flex-start;
    .ic { width: 28px; height: 28px; flex-shrink: 0; color: var(--md-sys-color-primary); }
    h3 { margin: 0; font-size: 1rem; font-weight: 600; }
    p.muted { font-size: 0.8125rem; margin: 2px 0 0; }
    .header-actions { margin-left: auto; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  }
}
.embed {
  background: var(--md-sys-color-surface); border-radius: 12px; min-height: 240px; overflow: hidden;
  :deep(.live-monitor) { padding: 0; min-height: 240px; display: flex; }
  :deep(.live-stage) { flex: 1; display: flex; align-items: center; justify-content: center; }
}
.placeholder {
  background: var(--md-sys-color-surface); border-radius: 12px; min-height: 240px;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  color: var(--md-sys-color-on-surface-variant);
  svg { width: 48px; height: 48px; opacity: 0.5; margin-bottom: 8px; }
}
.switch { position: relative; width: 40px; height: 22px; cursor: pointer; }
.switch input { opacity: 0; width: 0; height: 0; }
.knob {
  position: absolute; inset: 0; background: var(--md-sys-color-surface-variant);
  border-radius: 999px; transition: 0.2s;
}
.knob:before {
  content: ''; position: absolute; width: 16px; height: 16px;
  left: 3px; top: 3px; border-radius: 50%;
  background: var(--md-sys-color-on-surface-variant); transition: 0.2s;
}
.switch input:checked + .knob { background: var(--md-sys-color-primary); }
.switch input:checked + .knob:before { transform: translateX(18px); background: white; }
.chip-btn {
  display: inline-flex; gap: 6px; align-items: center;
  background: var(--md-sys-color-surface-variant); color: var(--md-sys-color-on-surface);
  border: none; padding: 6px 12px; border-radius: 999px; font-size: 0.8125rem;
  cursor: pointer;
  svg { width: 16px; height: 16px; }
  &.active { background: var(--md-sys-color-error); color: var(--md-sys-color-on-error); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.badge { display: inline-flex; align-items: center; gap: 6px; font-size: 0.75rem; padding: 4px 10px; border-radius: 999px; }
.badge-idle { background: var(--md-sys-color-surface-variant); color: var(--md-sys-color-on-surface-variant); }
.badge-rec { background: #c62828; color: white; }
.badge .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
.dot.pulse { animation: pulse 1s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1 } 50% { opacity: 0.4 } }

.ptt-row { display: grid; grid-template-columns: auto 1fr; gap: 16px; align-items: center; }
@media (max-width: 600px) { .ptt-row { grid-template-columns: 1fr; } }
.ptt-btn {
  background: var(--md-sys-color-primary); color: var(--md-sys-color-on-primary);
  border: none; border-radius: 999px; padding: 18px 28px; font-size: 1rem; font-weight: 600;
  cursor: pointer; user-select: none; display: inline-flex; gap: 10px; align-items: center;
  transition: transform 0.1s, background 0.2s;
  svg { width: 22px; height: 22px; }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}
.ptt-btn.active { background: #c62828; transform: scale(1.05); box-shadow: 0 0 0 8px rgba(198, 40, 40, 0.2); }
.ptt-info { color: var(--md-sys-color-on-surface); }
</style>
