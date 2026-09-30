<template>
  <div>
    <!-- 阶段标签：点一个只显示该阶段的罐 -->
    <div class="panel">
      <div class="panel-title">
        发酵罐阵列
        <span class="hint">圆柱罐体 + 椭圆封头 + 锥底，液位 = 当前批次产量 / 罐容</span>
      </div>
      <div class="stage-tabs">
        <button class="stage-tab" :class="{ 'is-on': activeStage === '' }" @click="activeStage = ''">
          全部 <b>{{ tanks.length }}</b>
        </button>
        <button
          v-for="s in stageTabs"
          :key="s.key"
          class="stage-tab"
          :class="{ 'is-on': activeStage === s.key }"
          @click="activeStage = s.key"
        >
          <span class="dot" :style="{ background: s.color }"></span>{{ s.name }}
          <b>{{ countOf(s.key) }}</b>
        </button>
      </div>

      <div class="tank-row">
        <div
          v-for="t in visibleTanks"
          :key="t.id"
          class="tank"
          :class="{ 'is-dim': activeStage !== '' && t.status !== activeStage }"
          @click="openTank(t)"
        >
          <div class="tank-body">
            <div class="tank-fill" :style="{ height: fillHeight(t), background: t.statusColor }">
              <div class="tank-fill-surface" :style="{ background: t.statusColor }"></div>
            </div>
            <div class="tank-cone"></div>
            <div class="tank-pct">{{ t.fillPercent }}%</div>
          </div>
          <div class="tank-no">{{ t.tankNo }}</div>
          <div class="tank-meta">{{ t.capacityL }} L · {{ t.materialName }}</div>
          <div class="tank-meta">{{ t.currentBatchNo || '空罐' }}</div>
          <span class="stage-badge" :style="{ background: t.statusColor }">{{ t.statusName }}</span>
        </div>
      </div>

      <div v-if="!visibleTanks.length" class="empty-tip">该阶段暂无发酵罐</div>
    </div>

    <!-- 罐 / 批次联动操作 -->
    <div class="panel">
      <div class="panel-title">罐体操作</div>
      <div class="toolbar">
        <el-select v-model="opForm.tankId" placeholder="选择发酵罐" style="width: 180px">
          <el-option v-for="t in tanks" :key="t.id" :label="t.tankNo + ' · ' + t.statusName" :value="t.id" />
        </el-select>
        <el-select v-model="opForm.batchNo" placeholder="选择批次入罐" clearable style="width: 220px">
          <el-option v-for="b in assignableBatches" :key="b.id" :label="b.batchNo + ' · ' + b.statusName" :value="b.batchNo" />
        </el-select>
        <el-button type="success" plain @click="assignBatch">批次入罐</el-button>
        <el-select v-model="opForm.status" placeholder="切换阶段" clearable style="width: 160px">
          <el-option v-for="s in stageTabs" :key="s.key" :label="s.name" :value="s.key" />
        </el-select>
        <el-button type="primary" plain @click="changeStatus">切换阶段</el-button>
        <el-button type="warning" plain @click="releaseTank">出罐清洗</el-button>
        <span class="spacer"></span>
        <el-button type="primary" @click="createVisible = true">新增发酵罐</el-button>
      </div>
    </div>

    <!-- 批次抽屉：工艺时间线 + 手绘 SVG 温度/糖度曲线 -->
    <el-drawer v-model="drawer" :title="drawerBatch ? drawerBatch.batchNo + ' · ' + drawerBatch.statusName : '批次详情'" size="560px">
      <div v-if="drawerBatch">
        <div class="summary-strip">
          <div class="summary-box"><b>{{ drawerBatch.volumeL }} L</b><span>产量</span></div>
          <div class="summary-box"><b>{{ drawerBatch.abv ?? '—' }}</b><span>ABV %vol</span></div>
          <div class="summary-box"><b>{{ drawerBatch.ibu ?? '—' }}</b><span>IBU</span></div>
          <div class="summary-box"><b>{{ drawerBatch.ebc ?? '—' }}</b><span>EBC</span></div>
        </div>

        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="配方">{{ drawerBatch.recipeName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="酿酒师">{{ drawerBatch.brewer }}</el-descriptions-item>
          <el-descriptions-item label="占用罐">{{ drawerBatch.fermenterNo || '未入罐' }}</el-descriptions-item>
          <el-descriptions-item label="酿造日期">{{ drawerBatch.brewDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="预计成熟">{{ drawerBatch.expectedMature || '—' }}</el-descriptions-item>
          <el-descriptions-item label="实际成熟">{{ drawerBatch.actualMature || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="panel-title">工艺时间线</div>
        <div class="timeline" style="margin-bottom: 14px">
          <div v-for="(node, i) in drawerBatch.timeline" :key="i" class="tl-item" :class="{ 'is-done': node.status === 'DONE' }">
            <div class="tl-name">{{ node.stageName }}</div>
            <div class="tl-time">{{ fmtTime(node.time) }}</div>
          </div>
        </div>

        <div class="panel-title">
          发酵曲线
          <span class="hint">纯 SVG 手绘 · 温度（℃）与表观糖度（°P）</span>
        </div>
        <svg v-if="curvePoints.length > 1" :viewBox="`0 0 ${W} ${H}`" style="width: 100%; height: 220px">
          <!-- 网格 -->
          <line v-for="g in 5" :key="'g' + g" :x1="PAD" :x2="W - PAD" :y1="PAD + (g / 5) * PH" :y2="PAD + (g / 5) * PH" stroke="#e3e8d9" stroke-width="1" />
          <line :x1="PAD" :x2="PAD" :y1="PAD" :y2="PAD + PH" stroke="#b6c0a8" />
          <line :x1="PAD" :x2="W - PAD" :y1="PAD + PH" :y2="PAD + PH" stroke="#b6c0a8" />
          <text :x="PAD - 4" :y="PAD + 4" text-anchor="end" font-size="10" fill="#8a9482">{{ maxTemp }}</text>
          <text :x="PAD - 4" :y="PAD + PH" text-anchor="end" font-size="10" fill="#8a9482">0</text>

          <polyline :points="tempPoints" fill="none" stroke="#c0392b" stroke-width="2" />
          <polyline :points="gravityPoints" fill="none" stroke="#7cb342" stroke-width="2" stroke-dasharray="5 3" />
          <g v-for="p in curvePoints" :key="p.dayKey">
            <circle :cx="p.x" :cy="p.ty" r="3" fill="#c0392b" />
            <circle :cx="p.x" :cy="p.gy" r="3" fill="#7cb342" />
            <text :x="p.x" :y="H - 6" text-anchor="middle" font-size="10" fill="#6b7760">{{ p.dayKey }}</text>
          </g>
        </svg>
        <div v-else class="empty-tip">该批次还没有逐日测量数据</div>
        <div class="legend">
          <span><i style="background: #c0392b"></i>温度 ℃</span>
          <span><i style="background: #7cb342"></i>表观糖度 °P</span>
        </div>
      </div>
      <div v-else class="empty-tip">该罐当前没有批次</div>
    </el-drawer>

    <!-- 新增发酵罐 -->
    <el-dialog v-model="createVisible" title="新增发酵罐" width="460px">
      <el-form :model="createForm" label-width="96px">
        <el-form-item label="罐号"><el-input v-model="createForm.tankNo" placeholder="如 FK-13" /></el-form-item>
        <el-form-item label="容量(L)"><el-input-number v-model="createForm.capacityL" :min="1" /></el-form-item>
        <el-form-item label="材质">
          <el-select v-model="createForm.material" style="width: 100%">
            <el-option label="不锈钢" value="STAINLESS" />
            <el-option label="夹套" value="JACKETED" />
            <el-option label="锥底" value="CONICAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="温控区间">
          <el-input-number v-model="createForm.tempMin" :precision="1" style="width: 45%" />
          <span style="margin: 0 6px">~</span>
          <el-input-number v-model="createForm.tempMax" :precision="1" style="width: 45%" />
        </el-form-item>
        <el-form-item label="冷却方式">
          <el-select v-model="createForm.cooling" style="width: 100%">
            <el-option label="乙二醇夹套" value="GLYCOL" />
            <el-option label="冰水盘管" value="ICE_WATER" />
            <el-option label="强制风冷" value="AIR" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { batchApi, fermenterApi } from '../api'

const W = 520
const H = 220
const PAD = 34
const PH = 150

const tanks = ref([])
const batches = ref([])
const stageTabs = ref([])
const activeStage = ref('')
const drawer = ref(false)
const drawerBatch = ref(null)
const createVisible = ref(false)
const opForm = ref({ tankId: null, batchNo: '', status: '' })
const createForm = ref({ tankNo: '', capacityL: 1000, material: 'STAINLESS', tempMin: 8, tempMax: 16, cooling: 'GLYCOL' })

const visibleTanks = computed(() =>
  activeStage.value === '' ? tanks.value : tanks.value.filter((t) => t.status === activeStage.value)
)

const assignableBatches = computed(() =>
  batches.value.filter((b) => b.status !== 'SCRAPPED' && b.status !== 'PACKAGED')
)

function countOf(status) {
  return tanks.value.filter((t) => t.status === status).length
}

/** 液位高度：0.4~96%，空罐不显示液体 */
function fillHeight(t) {
  const pct = Number(t.fillPercent || 0)
  if (pct <= 0) return '0px'
  return Math.min(96, pct) + '%'
}

function fmtTime(v) {
  if (!v) return '待进行'
  return String(v).replace('T', ' ').slice(0, 16)
}

const curvePoints = computed(() => {
  const pts = (drawerBatch.value && drawerBatch.value.curve) || []
  if (pts.length < 2) return []
  const maxTemp = Math.max(25, ...pts.map((p) => Number(p.temp || 0)))
  const stepX = (W - PAD * 2) / (pts.length - 1)
  const ty = (v) => PAD + PH - (Number(v || 0) / maxTemp) * PH
  const gy = (v) => PAD + PH - (Number(v || 0) / 20) * PH
  return pts.map((p, i) => ({
    dayKey: p.dayKey,
    x: PAD + i * stepX,
    ty: ty(p.temp),
    gy: gy(p.gravity)
  }))
})

const maxTemp = computed(() => {
  const pts = (drawerBatch.value && drawerBatch.value.curve) || []
  return Math.max(25, ...pts.map((p) => Number(p.temp || 0)))
})

const tempPoints = computed(() => curvePoints.value.map((p) => `${p.x},${p.ty}`).join(' '))
const gravityPoints = computed(() => curvePoints.value.map((p) => `${p.x},${p.gy}`).join(' '))

async function load() {
  tanks.value = await fermenterApi.list()
  batches.value = await batchApi.list()
  const dict = await fermenterApi.statusDict()
  stageTabs.value = dict.filter((d) => d.key !== 'DISABLED')
}

async function openTank(t) {
  if (!t.currentBatchNo) {
    drawerBatch.value = null
    drawer.value = true
    return
  }
  try {
    const list = await batchApi.list()
    drawerBatch.value = list.find((b) => b.batchNo === t.currentBatchNo) || null
    drawer.value = true
  } catch (e) {
    drawerBatch.value = null
  }
}

async function assignBatch() {
  if (!opForm.value.tankId || !opForm.value.batchNo) {
    ElMessage.warning('请先选择发酵罐和批次')
    return
  }
  await fermenterApi.assign(opForm.value.tankId, opForm.value.batchNo)
  ElMessage.success('批次已入罐')
  await load()
}

async function changeStatus() {
  if (!opForm.value.tankId || !opForm.value.status) {
    ElMessage.warning('请选择发酵罐与目标阶段')
    return
  }
  await fermenterApi.setStatus(opForm.value.tankId, { status: opForm.value.status })
  ElMessage.success('阶段已切换')
  await load()
}

async function releaseTank() {
  if (!opForm.value.tankId) {
    ElMessage.warning('请先选择发酵罐')
    return
  }
  await fermenterApi.release(opForm.value.tankId)
  ElMessage.success('已出罐并转入清洗')
  await load()
}

async function submitCreate() {
  await fermenterApi.create(createForm.value)
  ElMessage.success('发酵罐已新增')
  createVisible.value = false
  await load()
}

onMounted(load)
</script>
