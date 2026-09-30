<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">🍺</span>
        <div>
          精酿啤酒工坊 · 酿造运营平台
          <div class="brand-sub">{{ todayText }} · 在产 {{ overview.batchFermenting || 0 }} 批 · 罐容 {{ overview.capacityTotal || 0 }} L</div>
        </div>
      </div>

      <div class="top-stats">
        <div class="stat-chip"><b>{{ overview.tankBusy || 0 }}/{{ overview.tankTotal || 0 }}</b><span>在役罐</span></div>
        <div class="stat-chip"><b>{{ overview.tankIdle || 0 }}</b><span>空闲罐</span></div>
        <div class="stat-chip"><b>{{ overview.batchTotal || 0 }}</b><span>累计批次</span></div>
        <div class="stat-chip"><b>{{ overview.batchVolumeTotal || 0 }} L</b><span>总产量</span></div>
        <div class="stat-chip"><b>{{ overview.recipeTotal || 0 }}</b><span>配方</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.ingredientLow || 0) > 0 }">
          <b>{{ overview.ingredientLow || 0 }}</b><span>低库存原料</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.shipmentPending || 0) > 0 }">
          <b>{{ overview.shipmentPending || 0 }}</b><span>待发出货</span>
        </div>
      </div>
    </header>

    <nav class="navbar">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-btn"
        :class="{ 'is-on': activeKey === item.key }"
        type="button"
        :title="item.hint"
        @click="go(item.path)"
      >
        <span class="nav-ico">{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>

    <main class="stage">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { fermenterApi } from './api'

/** 模板自带的 main.js 只装了 Element Plus，router 在这里装到根实例上（install 幂等） */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()
const overview = ref({})

const todayText = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 · 周${week}`
})

const activeKey = computed(() => {
  const hit = navItems.find((i) => i.path === route.path)
  return hit ? hit.key : ''
})

function go(path) {
  nav.push(path)
}

async function loadOverview() {
  try {
    overview.value = await fermenterApi.overview()
  } catch (e) {
    overview.value = {}
  }
}

onMounted(loadOverview)
</script>
