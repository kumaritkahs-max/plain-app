import { defineStore } from 'pinia'
import { reactive } from 'vue'

export interface NlogGroup {
  key: string
  appId: string
  appName: string
  icon: string
  title: string
  body: string
  count: number
  lastTime: number
  times: number[]
  expanded: boolean
}

export interface NlogIngest {
  appId: string
  appName: string
  icon: string
  title: string
  body: string
  time: number
}

export const useNotificationsStore = defineStore('notifications-log', {
  state: () => ({
    groups: [] as NlogGroup[],
    seenKeys: new Set<string>(),
  }),
  actions: {
    add(d: NlogIngest) {
      const dedupe = `${d.appId}|${d.title}|${d.body}|${d.time}`
      if (this.seenKeys.has(dedupe)) return
      this.seenKeys.add(dedupe)
      const key = `${d.appId}|${d.title}|${d.body}`
      const existing = this.groups.find((g) => g.key === key)
      if (existing) {
        existing.count++
        existing.lastTime = Math.max(existing.lastTime, d.time)
        existing.times.push(d.time)
        // bubble to top by lastTime
        this.groups = [existing, ...this.groups.filter((g) => g !== existing)]
      } else {
        this.groups = [reactive<NlogGroup>({
          key,
          appId: d.appId,
          appName: d.appName,
          icon: d.icon,
          title: d.title,
          body: d.body,
          count: 1,
          lastTime: d.time,
          times: [d.time],
          expanded: false,
        }), ...this.groups]
      }
    },
    clear() { this.groups = []; this.seenKeys.clear() },
  },
})
