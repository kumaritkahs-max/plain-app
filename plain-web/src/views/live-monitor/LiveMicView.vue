<template>
  <div class="live-monitor">
    <Teleport v-if="isActive" to="#header-start-slot" defer>
      <div class="title">{{ $t('live_mic') }}</div>
    </Teleport>

    <Teleport v-if="isActive" to="#header-end-slot" defer>
      <div class="header-actions">
        <v-icon-button v-if="streaming" :tooltip="muted ? $t('unmute_mic') : $t('mute_mic')" @click="toggleMute">
          <i-lucide:volume-x v-if="muted" />
          <i-lucide:volume-2 v-else />
        </v-icon-button>
        <v-filled-button v-if="streaming" class="btn-sm" :loading="stopLoading" @click="stop">
          {{ $t('stop_live_mic') }}
        </v-filled-button>
      </div>
    </Teleport>

    <div class="live-stage">
      <div v-if="state === 'idle' || state === 'failed' || state === 'requesting'" class="idle-panel">
        <i-lucide:mic class="idle-icon" />
        <p class="idle-title">{{ $t('live_mic') }}</p>
        <p class="idle-hint">{{ $t('live_mic_idle_hint') }}</p>
        <v-filled-button :loading="startLoading" @click="start">{{ $t('start_live_mic') }}</v-filled-button>
        <p v-if="state === 'failed'" class="error-text">{{ $t('live_stream_failed') }}</p>
      </div>
      <div v-else class="audio-wrap">
        <audio ref="audioEl" autoplay />
        <div class="audio-card">
          <i-lucide:radio class="audio-icon" :class="{ active: state === 'streaming' && !muted }" />
          <p class="audio-title">{{ state === 'streaming' ? $t('live_mic_streaming_now') : $t('connecting') }}</p>
          <p v-if="muted" class="muted-text">{{ $t('mic_muted') }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import emitter from '@/plugins/eventbus'
import toast from '@/components/toaster'
import type { GqlError } from '@/lib/api/gql-client'
import { initLazyQuery, liveMicStateGQL } from '@/lib/api/query'
import {
  initMutation,
  startLiveMicGQL,
  stopLiveMicGQL,
  setLiveMicMutedGQL,
} from '@/lib/api/mutation'
import { WebRTCClient, type SignalingMessage } from '@/lib/webrtc-client'
import { makeSendWebRTCSignalingFor } from '@/lib/webrtc-signaling'
import { getPhoneIp } from '@/lib/api/api'

const { t } = useI18n()
const route = useRoute()
const isActive = computed(() => route.path === '/live-mic')

type LiveState = 'idle' | 'requesting' | 'connecting' | 'streaming' | 'failed'
const state = ref<LiveState>('idle')
const muted = ref(false)
const audioEl = ref<HTMLAudioElement>()

let client: WebRTCClient | null = null
const queue: SignalingMessage[] = []

function attach(stream: MediaStream) {
  if (audioEl.value) {
    audioEl.value.srcObject = stream
    audioEl.value.play().catch(() => {})
  }
}

function connect() {
  cleanupClient()
  client = new WebRTCClient({
    sendSignaling: makeSendWebRTCSignalingFor('mic'),
    onStream: (s) => { attach(s); state.value = 'streaming' },
    onConnectionStateChange: (s) => {
      if (s === 'failed' || s === 'disconnected' || s === 'closed') {
        if (state.value === 'streaming' || state.value === 'connecting') state.value = 'failed'
      }
    },
    onError: () => { state.value = 'failed' },
  })
  client.startSession(true, false, getPhoneIp(), { video: false, audio: true })
  while (queue.length) client.handleSignalingMessage(queue.shift()!)
}

function cleanupClient() {
  if (audioEl.value) { audioEl.value.pause(); audioEl.value.srcObject = null }
  client?.cleanup(); client = null
}

const onSignaling = (msg: any) => {
  if (!msg || msg.stream !== 'mic') return
  if (client) client.handleSignalingMessage(msg)
  else queue.push(msg)
}

const { fetch: fetchState } = initLazyQuery({
  handle: (data: any, error: string) => {
    if (error) { toast(t(error), 'error'); return }
    const s = data?.liveMicState
    if (s) muted.value = !!s.muted
    if (s?.running) {
      if (state.value === 'idle' || state.value === 'failed') { state.value = 'connecting'; connect() }
    } else {
      cleanupClient(); state.value = 'idle'
    }
  },
  document: liveMicStateGQL,
  variables: () => ({}),
  options: { fetchPolicy: 'no-cache' },
})

const { mutate: startMutate, loading: startLoading, onError: onStartError } =
  initMutation({ document: startLiveMicGQL }, false)
const { mutate: stopMutate, loading: stopLoading, onDone: onStopDone, onError: onStopError } =
  initMutation({ document: stopLiveMicGQL })
const { mutate: muteMutate } = initMutation({ document: setLiveMicMutedGQL })

const start = () => { state.value = 'requesting'; startMutate({}) }
const stop = () => stopMutate()
const toggleMute = () => { muted.value = !muted.value; muteMutate({ muted: muted.value }) }

onStartError((e: GqlError) => { toast(t(e.message), 'error'); state.value = 'failed' })
onStopDone(() => { cleanupClient(); state.value = 'idle' })
onStopError((e: GqlError) => toast(t(e.message), 'error'))

const onLiveMicStreaming = () => {
  if (state.value === 'streaming' || state.value === 'connecting') return
  state.value = 'connecting'; connect()
}

const streaming = computed(() => state.value === 'streaming' || state.value === 'connecting')

onMounted(() => {
  emitter.on('webrtc_signaling', onSignaling)
  emitter.on('live_mic_streaming', onLiveMicStreaming)
  fetchState()
})

onBeforeUnmount(() => {
  emitter.off('webrtc_signaling', onSignaling)
  emitter.off('live_mic_streaming', onLiveMicStreaming)
  cleanupClient()
})
</script>

<style scoped lang="scss">
.live-monitor { display: flex; flex-direction: column; height: 100%; }
.title { flex: 1; font-weight: 500; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.live-stage { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; }
.idle-panel, .audio-card {
  display: flex; flex-direction: column; align-items: center; gap: 12px;
  padding: 32px; border-radius: 16px;
  background: var(--md-sys-color-surface-container);
}
.idle-icon, .audio-icon { font-size: 48px; color: var(--md-sys-color-primary); }
.audio-icon.active { animation: pulse 1.6s ease-in-out infinite; }
@keyframes pulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}
.idle-title, .audio-title { font-size: 1.25rem; font-weight: 500; margin: 0; }
.idle-hint { color: var(--md-sys-color-on-surface-variant); margin: 0; text-align: center; }
.muted-text { color: var(--md-sys-color-error); margin: 0; }
.error-text { color: var(--md-sys-color-error); margin: 0; }
.audio-wrap { display: flex; align-items: center; justify-content: center; }
</style>
