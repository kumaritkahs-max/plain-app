import { defineStore } from 'pinia'

export interface TLEvent {
  id: string
  type: string
  title: string
  subtitle: string
  appId: string
  appName: string
  time: number
}

export const useTimelineStore = defineStore('timeline', {
  state: () => ({
    events: [] as TLEvent[],
    seen: new Set<string>(),
    serverStartedAt: 0 as number,
  }),
  actions: {
    add(e: TLEvent) {
      if (this.seen.has(e.id)) return
      this.seen.add(e.id)
      // newest first
      let inserted = false
      for (let i = 0; i < this.events.length; i++) {
        if (this.events[i].time <= e.time) {
          this.events.splice(i, 0, e); inserted = true; break
        }
      }
      if (!inserted) this.events.push(e)
      if (this.events.length > 1000) this.events = this.events.slice(0, 1000)
    },
    setServerStart(t: number) { if (t) this.serverStartedAt = t },
    clear() { this.events = []; this.seen.clear() },
  },
})
