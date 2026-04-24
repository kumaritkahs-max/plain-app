<template>
  <div v-if="visible" class="live-call-card" :class="{ ringing: state.state === 'ringing', active: state.state === 'active' }">
    <div class="call-info">
      <div class="avatar" :class="state.source">
        <i-lucide:phone-incoming v-if="state.state === 'ringing'" />
        <i-lucide:phone-call v-else-if="state.state === 'active'" />
        <i-lucide:phone v-else />
      </div>
      <div class="meta">
        <div class="source-row">
          <span class="badge" :class="state.source">{{ sourceLabel }}</span>
          <span v-if="state.state === 'ringing'" class="ringing-text">{{ $t('incoming_call') }}…</span>
          <span v-else-if="state.state === 'active'" class="active-text">{{ $t('on_call') }} · {{ duration }}</span>
        </div>
        <div class="display">{{ state.display || state.appName || $t('unknown') }}</div>
      </div>
    </div>

    <div class="actions">
      <template v-if="state.state === 'ringing'">
        <button class="btn btn-end" @click="end" v-tooltip="$t('decline')">
          <i-lucide:phone-off /> <span>{{ $t('decline') }}</span>
        </button>
        <button class="btn btn-accept pulse" @click="accept" v-tooltip="$t('answer')">
          <i-lucide:phone /> <span>{{ $t('answer_speakerphone') }}</span>
        </button>
      </template>
      <template v-else-if="state.state === 'active'">
        <button class="btn btn-secondary" @click="toggleMute">
          <i-lucide:mic-off v-if="state.muted" /><i-lucide:mic v-else />
          <span>{{ state.muted ? $t('unmute') : $t('mute') }}</span>
        </button>
        <button class="btn btn-end" @click="end">
          <i-lucide:phone-off /> <span>{{ $t('hang_up') }}</span>
        </button>
      </template>
    </div>

    <audio v-if="state.state === 'active'" ref="audioEl" autoplay playsinline />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import emitter from '@/plugins/eventbus'
import { gqlFetch } from '@/lib/api/gql-client'
import { liveCallStateGQL } from '@/lib/api/query'
import { initMutation, acceptLiveCallGQL, endLiveCallGQL, setLiveCallMutedGQL } from '@/lib/api/mutation'
import type { ILiveCallState } from '@/lib/interfaces'
import { WebRTCClient, type SignalingMessage } from '@/lib/webrtc-client'
import { makeSendWebRTCSignalingFor } from '@/lib/webrtc-signaling'
import { getPhoneIp } from '@/lib/api/api'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const state = ref<ILiveCallState>({ state: 'idle', direction: 'incoming', source: 'phone', appId: '', appName: '', display: '', startedAt: 0, acceptedAt: 0, muted: false })
const audioEl = ref<HTMLAudioElement | null>(null)
const visible = computed(() => state.value.state === 'ringing' || state.value.state === 'active')
const sourceLabel = computed(() => ({
  phone: 'Phone', whatsapp: 'WhatsApp', telegram: 'Telegram', signal: 'Signal', messenger: 'Messenger',
} as any)[state.value.source] || state.value.source)

const tickNow = ref(Date.now())
let tickHandle: any
const duration = computed(() => {
  void tickNow.value
  if (!state.value.acceptedAt) return ''
  const sec = Math.floor((Date.now() - state.value.acceptedAt) / 1000)
  const m = Math.floor(sec / 60); const s = sec % 60
  return `${m}:${s.toString().padStart(2, '0')}`
})

const { mutate: mAccept } = initMutation({ document: acceptLiveCallGQL })
const { mutate: mEnd } = initMutation({ document: endLiveCallGQL })
const { mutate: mMute } = initMutation({ document: setLiveCallMutedGQL })

let client: WebRTCClient | null = null
const signalQueue: SignalingMessage[] = []

function attach(stream: MediaStream) {
  if (audioEl.value) { audioEl.value.srcObject = stream; audioEl.value.play().catch(() => {}) }
}
function startStreaming() {
  stopStreaming()
  client = new WebRTCClient({
    sendSignaling: makeSendWebRTCSignalingFor('mic'),
    onStream: attach,
    onError: () => {},
  })
  client.startSession(true, false, getPhoneIp(), { video: false, audio: true })
  while (signalQueue.length) client.handleSignalingMessage(signalQueue.shift()!)
}
function stopStreaming() {
  if (audioEl.value) { audioEl.value.pause(); audioEl.value.srcObject = null }
  client?.cleanup(); client = null
}
const onSignaling = (msg: any) => {
  if (!msg || msg.stream !== 'mic') return
  if (client) client.handleSignalingMessage(msg)
  else signalQueue.push(msg)
}

async function accept() {
  await mAccept()
}
async function end() {
  await mEnd()
}
async function toggleMute() {
  await mMute({ muted: !state.value.muted })
}

function onState(d: any) {
  if (!d) return
  state.value = d
}

watch(() => state.value.state, async (s) => {
  if (s === 'active') {
    await new Promise(r => setTimeout(r, 50))
    startStreaming()
  } else {
    stopStreaming()
  }
})

async function loadOnce() {
  try {
    const r = await gqlFetch<{ liveCallState: ILiveCallState }>(liveCallStateGQL)
    if (!r.errors) state.value = r.data.liveCallState
  } catch {}
}

onMounted(() => {
  emitter.on('live_call_state', onState)
  emitter.on('webrtc_signaling', onSignaling)
  loadOnce()
  tickHandle = setInterval(() => { tickNow.value = Date.now() }, 1000)
})
onUnmounted(() => {
  emitter.off('live_call_state', onState)
  emitter.off('webrtc_signaling', onSignaling)
  stopStreaming()
  if (tickHandle) clearInterval(tickHandle)
})
</script>

<style scoped lang="scss">
.live-call-card {
  grid-column: span 2;
  background: linear-gradient(135deg, var(--md-sys-color-primary), var(--md-sys-color-tertiary));
  color: white;
  border-radius: 18px;
  padding: 18px 20px;
  display: flex; gap: 16px; align-items: center; flex-wrap: wrap;
  box-shadow: 0 8px 24px rgba(0,0,0,0.18);
  &.ringing { animation: ring 1.4s infinite; }
}
@keyframes ring {
  0%, 100% { box-shadow: 0 8px 24px rgba(0,0,0,0.18); }
  50% { box-shadow: 0 0 0 6px rgba(255,255,255,0.18), 0 8px 24px rgba(0,0,0,0.28); }
}
.call-info { display: flex; align-items: center; gap: 14px; flex: 1; min-width: 240px; }
.avatar {
  width: 56px; height: 56px; border-radius: 50%;
  background: rgba(255,255,255,0.2);
  display: flex; align-items: center; justify-content: center;
  svg { width: 28px; height: 28px; }
}
.meta { display: flex; flex-direction: column; gap: 4px; }
.source-row { display: flex; gap: 8px; align-items: center; font-size: 0.8125rem; opacity: 0.95; }
.badge { background: rgba(255,255,255,0.2); padding: 2px 10px; border-radius: 999px; text-transform: uppercase; font-weight: 700; font-size: 0.7rem; letter-spacing: 0.5px; }
.display { font-size: 1.25rem; font-weight: 700; }
.actions { display: flex; gap: 10px; align-items: center; }
.btn {
  display: inline-flex; align-items: center; gap: 6px;
  border: none; padding: 12px 18px; border-radius: 999px;
  font-weight: 600; cursor: pointer; font-size: 0.9rem;
  svg { width: 18px; height: 18px; }
}
.btn-accept { background: #2e7d32; color: white; }
.btn-end { background: #c62828; color: white; }
.btn-secondary { background: rgba(255,255,255,0.18); color: white; }
.pulse { animation: btn-pulse 1s infinite; }
@keyframes btn-pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.06); box-shadow: 0 0 0 6px rgba(46, 125, 50, 0.35); }
}
.ringing-text, .active-text { font-weight: 500; }
@media (max-width: 600px) {
  .live-call-card { flex-direction: column; align-items: stretch; }
  .actions { justify-content: stretch; }
  .actions .btn { flex: 1; justify-content: center; }
}
</style>
