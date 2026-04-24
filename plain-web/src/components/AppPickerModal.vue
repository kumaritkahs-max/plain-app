<template>
  <v-modal @close="cancel">
    <template #headline>{{ title || $t('select_apps') }}</template>
    <template #content>
      <v-text-field v-model="filter" :placeholder="$t('search')" class="filter" />
      <div class="list" v-if="!loading">
        <label v-for="p in filtered" :key="p.id" class="row">
          <v-checkbox touch-target="wrapper" :checked="picked.has(p.id)" @change="toggle(p.id)" />
          <img class="icon" width="28" height="28" :src="getIcon(p.id)" />
          <div class="meta">
            <div class="name">{{ p.name }}</div>
            <div class="pkg">{{ p.id }}</div>
          </div>
        </label>
        <div v-if="filtered.length === 0" class="muted">{{ $t('no_data') }}</div>
      </div>
      <div v-else class="muted">{{ $t('loading') }}</div>
    </template>
    <template #actions>
      <v-outlined-button @click="cancel">{{ $t('cancel') }}</v-outlined-button>
      <v-filled-button @click="confirm">{{ $t('save') }} ({{ picked.size }})</v-filled-button>
    </template>
  </v-modal>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { popModal } from './modal'
import { gqlFetch } from '@/lib/api/gql-client'
import { packagesGQL } from '@/lib/api/query'
import { useTempStore } from '@/stores/temp'
import { storeToRefs } from 'pinia'
import { getFileUrlByPath } from '@/lib/api/file'

interface PkgRow { id: string; name: string }

const props = defineProps<{
  title?: string
  initial: string[]
  done: (ids: string[]) => void
}>()

const { urlTokenKey } = storeToRefs(useTempStore())
const filter = ref('')
const loading = ref(true)
const apps = ref<PkgRow[]>([])
const picked = ref(new Set<string>(props.initial))

function getIcon(id: string) {
  return getFileUrlByPath(urlTokenKey.value, 'pkgicon://' + id)
}

const filtered = computed(() => {
  const q = filter.value.trim().toLowerCase()
  if (!q) return apps.value
  return apps.value.filter((p) => p.name.toLowerCase().includes(q) || p.id.toLowerCase().includes(q))
})

function toggle(id: string) {
  const s = new Set(picked.value)
  if (s.has(id)) s.delete(id); else s.add(id)
  picked.value = s
}
function cancel() { popModal() }
function confirm() { props.done(Array.from(picked.value)); popModal() }

;(async () => {
  try {
    const r = await gqlFetch<{ packages: any[] }>(packagesGQL, { offset: 0, limit: 1000, query: '', sortBy: 'NAME_ASC' })
    if (!r.errors) apps.value = r.data.packages.map((p) => ({ id: p.id, name: p.name }))
  } finally { loading.value = false }
})()
</script>

<style scoped>
.filter { width: 100%; margin-bottom: 8px; }
.list { max-height: 50vh; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
.row { display: flex; gap: 10px; align-items: center; padding: 6px 8px; border-radius: 8px; cursor: pointer; }
.row:hover { background: var(--md-sys-color-surface-variant); }
.icon { border-radius: 6px; }
.meta { flex: 1; min-width: 0; }
.name { font-size: 0.9rem; font-weight: 500; }
.pkg { font-size: 0.75rem; color: var(--md-sys-color-on-surface-variant); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.muted { color: var(--md-sys-color-on-surface-variant); padding: 16px; text-align: center; }
</style>
