<template>
  <div>
    <div class="panel">
      <div class="panel-title">
        啤酒批次
        <span class="hint">计划 → 糖化 → 煮沸 → 发酵 → 熟成 → 已灌装 / 报废；熟成放行后才能灌装出货</span>
      </div>

      <div class="summary-strip">
        <div class="summary-box"><b>{{ summary.batchTotal || 0 }}</b><span>批次总数</span></div>
        <div class="summary-box"><b>{{ summary.volumeTotal || 0 }} L</b><span>累计产量</span></div>
        <div class="summary-box"><b>{{ summary.packagedVolume || 0 }} L</b><span>已灌装产量</span></div>
        <div v-for="item in summary.items" :key="item.status" class="summary-box">
          <b>{{ item.count }}</b><span>{{ item.statusName }}（{{ item.totalVolume }} L）</span>
        </div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" clearable placeholder="按状态筛选" style="width: 180px" @change="load">
          <el-option label="计划" value="PLAN" />
          <el-option label="糖化" value="MASH" />
          <el-option label="煮沸" value="BOIL" />
          <el-option label="发酵" value="FERMENT" />
          <el-option label="熟成" value="CONDITION" />
          <el-option label="已灌装" value="PACKAGED" />
          <el-option label="报废" value="SCRAPPED" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="createVisible = true">新增批次</el-button>
      </div>

      <el-table :data="rows" border stripe size="small">
        <el-table-column prop="batchNo" label="批号" width="130" />
        <el-table-column prop="recipeName" label="配方" min-width="150" />
        <el-table-column prop="fermenterNo" label="占用罐" width="90" />
        <el-table-column prop="brewer" label="酿酒师" width="90" />
        <el-table-column label="工艺节点" min-width="200">
          <template #default="{ row }">
            <span style="font-size: 12px">
              糖化 {{ fmt(row.mashStart) }} | 煮沸终 {{ fmt(row.boilEnd) }} | 发酵启 {{ fmt(row.fermentStart) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="expectedMature" label="预计成熟" width="110" />
        <el-table-column prop="volumeL" label="产量(L)" width="90" />
        <el-table-column label="批次状态" width="90">
          <template #default="{ row }">
            <span class="tag-inline" :style="{ background: statusColor(row.status) }">{{ row.statusName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="质检结论" min-width="180">
          <template #default="{ row }">
            <el-tooltip v-if="row.qcReason" :content="row.qcReason" placement="top" :show-after="200">
              <span class="tag-inline qc-tag" :style="{ background: qcColor(row.qcStatus) }">
                {{ row.qcStatusName || '待质检' }}<span v-if="row.qcRoundSeq"> · R{{ row.qcRoundSeq }}</span>
              </span>
            </el-tooltip>
            <span v-else class="tag-inline qc-tag" :style="{ background: qcColor(row.qcStatus) }">
              {{ row.qcStatusName || '待质检' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openDetail(row)">质检/详情</el-button>
            <el-button v-if="row.nextStatus" size="small" type="success" plain @click="advance(row)">
              {{ row.nextStatus === 'PACKAGED' ? '完成灌装' : '推进到' + row.nextStatusName }}
            </el-button>
            <el-button v-if="!isEnd(row)" size="small" type="danger" plain @click="scrap(row)">报废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="新增批次" width="520px">
      <el-form :model="createForm" label-width="96px">
        <el-form-item label="批号"><el-input v-model="createForm.batchNo" placeholder="如 BC-2026-114" /></el-form-item>
        <el-form-item label="配方">
          <el-select v-model="createForm.recipeId" style="width: 100%">
            <el-option v-for="r in recipes" :key="r.id" :label="r.name + ' · ' + r.styleName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="酿酒师"><el-input v-model="createForm.brewer" /></el-form-item>
        <el-form-item label="酿造日期"><el-date-picker v-model="createForm.brewDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="预计产量(L)"><el-input-number v-model="createForm.volumeL" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 批次详情 + 质检放行 ============ -->
    <el-dialog v-model="detailVisible" :title="`批次详情 · ${detail.batchNo || ''}`" width="900px" top="6vh">
      <div v-if="detail.id">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="配方">{{ detail.recipeName }}</el-descriptions-item>
          <el-descriptions-item label="酿酒师">{{ detail.brewer }}</el-descriptions-item>
          <el-descriptions-item label="批次状态">
            <span class="tag-inline" :style="{ background: statusColor(detail.status) }">{{ detail.statusName }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="预计成熟">{{ detail.expectedMature || '—' }}</el-descriptions-item>
          <el-descriptions-item label="实际成熟">{{ detail.actualMature || '—' }}</el-descriptions-item>
          <el-descriptions-item label="产量">{{ detail.volumeL }} L</el-descriptions-item>
          <el-descriptions-item label="ABV">{{ detail.abv ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="占用罐">{{ detail.fermenterNo || '—' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">逐日温度 / 糖度</el-divider>
        <el-table :data="detail.curve || []" size="small" border max-height="200">
          <el-table-column prop="dayKey" label="第 N 天" width="100" />
          <el-table-column prop="temp" label="温度 ℃" />
          <el-table-column prop="gravity" label="糖度 °P" />
        </el-table>
        <div style="margin-top: 8px">
          <el-button size="small" plain @click="openReading(detail)">补录测量</el-button>
        </div>

        <!-- 创建当时的配方用量快照（历史副本，配方后续改动不影响本批次） -->
        <template v-if="detail.recipeSnapshot">
          <el-divider content-position="left">
            创建当时配方用量快照
            <el-tag size="small" :type="detail.recipeSnapshot.migrated ? 'info' : 'success'" style="margin-left: 8px">
              {{ detail.recipeSnapshot.migrated ? '历史批次迁移回填' : '建档时固化' }}
            </el-tag>
          </el-divider>
          <el-alert v-if="detail.recipeSnapshot.reviewCount > 0" type="warning" :closable="false" show-icon
                    style="margin-bottom: 8px"
                    :title="`该快照有 ${detail.recipeSnapshot.reviewCount} 条历史单位无法可靠换算，已保留原值但不计入合计`" />
          <el-table :data="detail.recipeSnapshot.items" size="small" border max-height="240">
            <el-table-column prop="stageName" label="阶段" width="105" />
            <el-table-column prop="ingredientName" label="原料" min-width="110" />
            <el-table-column label="当时用量" width="110">
              <template #default="{ row }">
                <span :class="{ 'snap-review': row.needsReview }">
                  {{ row.quantity }} {{ row.unit || '（空）' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="规范化" width="120">
              <template #default="{ row }">
                <el-tooltip v-if="row.unitNote" :content="row.unitNote" placement="top" :show-after="100">
                  <span v-if="row.needsReview" class="snap-review">待处理</span>
                  <span v-else :class="row.dimMismatch ? 'snap-mismatch' : ''">
                    {{ row.normalizedQuantity }} {{ baseUnit(row.dimension) }}
                  </span>
                </el-tooltip>
                <span v-else-if="row.needsReview" class="snap-review">待处理</span>
                <span v-else>{{ row.normalizedQuantity }} {{ baseUnit(row.dimension) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.unitNote || '—' }}</template>
            </el-table-column>
          </el-table>
          <div style="margin-top: 6px; font-size: 12px">
            规范化合计：
            <b v-for="t in detail.recipeSnapshot.totals" :key="t.dimension" style="margin-right: 14px">
              {{ t.dimensionName }} {{ t.amount }} {{ t.baseUnit }}
            </b>
            <span style="color: #909399">（质量与体积分列，按创建当时用量计算，不随后续配方变化）</span>
          </div>
        </template>

        <el-divider content-position="left">
          质检放行
          <span class="tag-inline qc-tag" :style="{ background: qcColor(qc.current?.status), marginLeft: '10px' }">
            {{ qc.current?.statusName || '待质检' }}
            <span v-if="qc.current?.roundSeq"> · 第 {{ qc.current.roundSeq }} 轮</span>
          </span>
        </el-divider>
        <el-alert v-if="qc.current?.reason" :title="qc.current.reason" :type="qcAlertType(qc.current.status)"
                  :closable="false" show-icon style="margin-bottom: 10px" />

        <!-- 操作区 -->
        <div style="margin-bottom: 12px" v-loading="qcBusy">
          <template v-if="detail.status !== 'SCRAPPED'">
            <el-button type="primary" plain size="small" :disabled="openRoundPending" @click="openSubmitRound">
              {{ latestQcRound && latestQcRound.status === 'PENDING' ? '已有检验中轮次' : (qc.rounds?.length ? '发起新一轮复检' : '发起首检') }}
            </el-button>
            <el-button v-if="latestQcRound && latestQcRound.status === 'PENDING'"
                       type="success" size="small" @click="openDecide('PASS')">通过放行</el-button>
            <el-button v-if="latestQcRound && latestQcRound.status === 'PENDING'"
                       type="danger" plain size="small" @click="openDecide('REJECT')">驳回</el-button>
            <el-button v-if="latestQcRound && latestQcRound.status === 'PASS'"
                       type="warning" plain size="small" @click="openRevoke">撤销放行</el-button>
            <span v-if="!canQcNow" style="margin-left: 10px; color: #909399; font-size: 12px">
              发酵开始后方可送检
            </span>
          </template>
          <span v-else style="color: #c0392b; font-size: 12px">批次已报废，质检与下游操作全部关闭</span>
        </div>

        <!-- 历史轮次（含测量快照，只读不可改） -->
        <el-table :data="qc.rounds || []" size="small" border>
          <el-table-column label="轮次" width="70">
            <template #default="{ row }">第 {{ row.roundSeq }} 轮</template>
          </el-table-column>
          <el-table-column label="送检时批次" width="110">
            <template #default="{ row }">{{ row.batchStatusSnapshotName }}</template>
          </el-table-column>
          <el-table-column label="结论" width="90">
            <template #default="{ row }">
              <span class="tag-inline qc-tag" :style="{ background: qcColor(row.status) }">{{ row.statusName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="abv" label="ABV" width="70" />
          <el-table-column prop="sensory" label="感官结论" min-width="180" show-overflow-tooltip />
          <el-table-column prop="submittedBy" label="送检人" width="90" />
          <el-table-column label="送检时间" width="145">
            <template #default="{ row }">{{ dt(row.submittedAt) }}</template>
          </el-table-column>
          <el-table-column label="结论/撤销原因" min-width="180">
            <template #default="{ row }">
              <div v-if="row.status === 'REVOKED'">
                <div style="color: #b88230">撤销：{{ row.revokeReason }}（{{ row.revokedBy }} {{ dt(row.revokedAt) }}）</div>
                <div style="color: #5b8a2a; font-size: 12px">原放行：{{ row.conclusionNote }}</div>
              </div>
              <span v-else>{{ row.conclusionNote || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="送检测量摘要" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showReadings(row)">查看 {{ row.readings?.length || 0 }} 天</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">操作流水（留痕，不可篡改）</el-divider>
        <el-timeline style="padding-left: 6px">
          <el-timeline-item v-for="a in qc.actions || []" :key="a.id"
                            :timestamp="`${a.operator} · ${dt(a.createdAt)}`" placement="top"
                            :type="a.action === 'REVOKE' || a.action === 'REJECT' ? 'warning' : (a.action === 'PASS' ? 'success' : 'primary')">
            <b>第 {{ a.roundSeq }} 轮 · {{ a.actionName }}</b>
            <span v-if="a.reason" style="margin-left: 8px">{{ a.reason }}</span>
          </el-timeline-item>
        </el-timeline>
      </div>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 发起送检 -->
    <el-dialog v-model="submitVisible" title="发起质检送检" width="520px" append-to-body>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
        title="送检时将快照批次当前状态与逐日温度/糖度；提交后不可原地修改，复检只能开新一轮。" />
      <el-form label-width="92px">
        <el-form-item label="送检操作人">
          <el-input v-model="submitForm.operator" :placeholder="detail.brewer || '操作人姓名'" />
        </el-form-item>
        <el-form-item label="送检备注">
          <el-input v-model="submitForm.note" type="textarea" :rows="2" placeholder="如：熟成第 20 天送检" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" :loading="qcBusy" @click="submitRound">确认送检</el-button>
      </template>
    </el-dialog>

    <!-- 下结论 -->
    <el-dialog v-model="decideVisible" :title="decideForm.decision === 'PASS' ? '质检放行' : '质检驳回'" width="520px" append-to-body>
      <el-form label-width="92px">
        <el-form-item label="操作人"><el-input v-model="decideForm.operator" /></el-form-item>
        <el-form-item label="酒精度 ABV" v-if="decideForm.decision === 'PASS'">
          <el-input-number v-model="decideForm.abv" :precision="2" :step="0.1" :min="0" />
          <span style="color:#909399;font-size:12px;margin-left:8px">放行必填</span>
        </el-form-item>
        <el-form-item label="感官结论">
          <el-input v-model="decideForm.sensory" type="textarea" :rows="2" placeholder="外观 / 香气 / 口味" />
        </el-form-item>
        <el-form-item :label="decideForm.decision === 'PASS' ? '放行说明' : '驳回原因'">
          <el-input v-model="decideForm.reason" type="textarea" :rows="2"
                    :placeholder="decideForm.decision === 'PASS' ? '可留空' : '驳回必填，将冻结未发出货单'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decideVisible = false">取消</el-button>
        <el-button :type="decideForm.decision === 'PASS' ? 'success' : 'danger'" :loading="qcBusy" @click="submitDecide">
          {{ decideForm.decision === 'PASS' ? '确认放行' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 撤销放行 -->
    <el-dialog v-model="revokeVisible" title="撤销放行" width="520px" append-to-body>
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
        title="撤销后未发出货单将进入冻结，需重新发起一轮复检并放行后才能恢复；原放行结论与流水永久保留。" />
      <el-form label-width="92px">
        <el-form-item label="操作人"><el-input v-model="revokeForm.operator" /></el-form-item>
        <el-form-item label="撤销原因" required>
          <el-input v-model="revokeForm.reason" type="textarea" :rows="3" placeholder="必须填写撤销原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="revokeVisible = false">取消</el-button>
        <el-button type="warning" :loading="qcBusy" @click="submitRevoke">确认撤销</el-button>
      </template>
    </el-dialog>

    <!-- 轮次测量快照查看 -->
    <el-dialog v-model="readingsVisible" title="送检时逐日测量快照" width="480px" append-to-body>
      <el-table :data="readingsData" size="small" border>
        <el-table-column prop="dayKey" label="第 N 天" width="120" />
        <el-table-column prop="temp" label="温度 ℃" />
        <el-table-column prop="gravity" label="糖度 °P" />
      </el-table>
    </el-dialog>

    <!-- 补录测量（复用原曲线表） -->
    <el-dialog v-model="readingVisible" :title="`逐日测温 / 测糖 · ${detail.batchNo || ''}`" width="520px" append-to-body>
      <el-table :data="detail.curve || []" size="small" border max-height="240">
        <el-table-column prop="dayKey" label="第 N 天" width="100" />
        <el-table-column prop="temp" label="温度 ℃" />
        <el-table-column prop="gravity" label="糖度 °P" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain @click="removeReading(row.dayKey)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-divider />
      <el-form :inline="true" :model="readingForm">
        <el-form-item label="天数"><el-input v-model="readingForm.dayKey" placeholder="D7" style="width: 90px" /></el-form-item>
        <el-form-item label="温度"><el-input-number v-model="readingForm.temp" :precision="1" :step="0.5" style="width: 120px" /></el-form-item>
        <el-form-item label="糖度"><el-input-number v-model="readingForm.gravity" :precision="1" :step="0.5" style="width: 120px" /></el-form-item>
        <el-form-item><el-button type="primary" @click="submitReading">登记</el-button></el-form-item>
      </el-form>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { batchApi, qcApi, recipeApi } from '../api'

const rows = ref([])
const recipes = ref([])
const summary = ref({})
const statusFilter = ref('')
const createVisible = ref(false)
const createForm = ref({ batchNo: '', recipeId: null, brewer: '', brewDate: '', volumeL: 1000 })

const detailVisible = ref(false)
const detail = ref({})
const qc = ref({ rounds: [], actions: [], current: null })
const qcBusy = ref(false)

const submitVisible = ref(false)
const submitForm = ref({ operator: '', note: '', clientToken: '' })
const decideVisible = ref(false)
const decideForm = ref({ decision: 'PASS', operator: '', abv: null, sensory: '', reason: '', version: null })
const revokeVisible = ref(false)
const revokeForm = ref({ operator: '', reason: '', version: null })
const readingsVisible = ref(false)
const readingsData = ref([])

const readingVisible = ref(false)
const readingForm = ref({ dayKey: 'D1', temp: 19, gravity: 12 })

const COLOR = {
  PLAN: '#9aa5ab', MASH: '#f0d58a', BOIL: '#ef9b52', FERMENT: '#8bc34a',
  CONDITION: '#d99a2b', PACKAGED: '#5b8a2a', SCRAPPED: '#c0392b'
}
const QC_COLORS = {
  NONE: '#c0c4cc', PENDING: '#e6a23c', REINSPECT: '#e6a23c',
  PASS: '#67c23a', REJECT: '#f56c6c', REVOKED: '#909399'
}
function statusColor(status) { return COLOR[status] || '#8d9aa5' }
function qcColor(s) { return QC_COLORS[s] || QC_COLORS.NONE }
function qcAlertType(s) {
  if (s === 'PASS') return 'success'
  if (s === 'REJECT') return 'error'
  if (s === 'REVOKED' || s === 'REINSPECT' || s === 'PENDING') return 'warning'
  return 'info'
}

function fmt(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(5, 16)
}
function baseUnit(dim) {
  return { MASS: 'g', VOLUME: 'mL', COUNT: 'pkg' }[dim] || ''
}
function dt(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}
function isEnd(row) { return row.status === 'PACKAGED' || row.status === 'SCRAPPED' }

const latestQcRound = computed(() => {
  const rs = qc.value.rounds || []
  return rs.length ? rs[rs.length - 1] : null
})
const canQcNow = computed(() =>
  ['FERMENT', 'CONDITION', 'PACKAGED'].includes(detail.value.status))
const openRoundPending = computed(() =>
  (latestQcRound.value && latestQcRound.value.status === 'PENDING') || !canQcNow.value)

async function load() {
  rows.value = await batchApi.list(statusFilter.value)
  summary.value = await batchApi.summary()
}

async function advance(row) {
  try {
    await batchApi.advance(row.id, row.nextStatus)
    ElMessage.success('批次已推进到 ' + row.nextStatusName)
    await load()
  } catch (e) { /* 拦截原因已统一弹出 */ }
}

async function scrap(row) {
  try {
    await ElMessageBox.confirm(
      '报废后批次不能再质检/灌装/出货，未发出货单将永久冻结，已发/已结款单据保留。确认报废？',
      '批次报废', { type: 'warning', confirmButtonText: '确认报废', cancelButtonText: '取消' })
  } catch (e) { return }
  await batchApi.scrap(row.id)
  ElMessage.success('批次已报废')
  await load()
}

async function submitCreate() {
  await batchApi.create(createForm.value)
  ElMessage.success('批次已建档')
  createVisible.value = false
  await load()
}

// ---------------- 质检 ----------------
async function openDetail(row) {
  detailVisible.value = true
  detail.value = await batchApi.get(row.id)
  await loadQc()
}

async function loadQc() {
  qc.value = await qcApi.profile(detail.value.id)
}

function openSubmitRound() {
  submitForm.value = {
    operator: detail.value.brewer || '',
    note: qc.value.rounds?.length ? '申请复检' : '',
    clientToken: (crypto?.randomUUID && crypto.randomUUID()) || String(Date.now()) + Math.random().toString(16)
  }
  submitVisible.value = true
}

async function submitRound() {
  if (!submitForm.value.operator) { ElMessage.warning('请填写送检操作人'); return }
  qcBusy.value = true
  try {
    await qcApi.openRound(detail.value.id, submitForm.value)
    ElMessage.success('已送检')
    submitVisible.value = false
    await loadQc()
    await load()
  } finally { qcBusy.value = false }
}

function openDecide(decision) {
  decideForm.value = {
    decision,
    operator: latestQcRound.value?.submittedBy || detail.value.brewer || '',
    abv: detail.value.abv,
    sensory: latestQcRound.value?.sensory || '',
    reason: '',
    version: latestQcRound.value?.version
  }
  decideVisible.value = true
}

async function submitDecide() {
  if (!decideForm.value.operator) { ElMessage.warning('请填写操作人'); return }
  if (!decideForm.value.sensory) { ElMessage.warning('请填写感官结论'); return }
  if (decideForm.value.decision === 'PASS' && decideForm.value.abv == null) {
    ElMessage.warning('放行必须填写酒精度'); return
  }
  if (decideForm.value.decision === 'REJECT' && !decideForm.value.reason) {
    ElMessage.warning('驳回必须填写原因'); return
  }
  qcBusy.value = true
  try {
    await qcApi.decide(detail.value.id, latestQcRound.value.id, decideForm.value)
    ElMessage.success(decideForm.value.decision === 'PASS' ? '已放行' : '已驳回')
    decideVisible.value = false
    await loadQc()
    await load()
    detail.value = await batchApi.get(detail.value.id)
  } finally { qcBusy.value = false }
}

function openRevoke() {
  revokeForm.value = { operator: '', reason: '', version: latestQcRound.value?.version }
  revokeVisible.value = true
}

async function submitRevoke() {
  if (!revokeForm.value.operator) { ElMessage.warning('请填写操作人'); return }
  if (!revokeForm.value.reason) { ElMessage.warning('撤销必须填写原因'); return }
  qcBusy.value = true
  try {
    await qcApi.revoke(detail.value.id, latestQcRound.value.id, revokeForm.value)
    ElMessage.success('放行已撤销，未发出货单已冻结')
    revokeVisible.value = false
    await loadQc()
    await load()
  } finally { qcBusy.value = false }
}

function showReadings(round) {
  readingsData.value = round.readings || []
  readingsVisible.value = true
}

// ---------------- 批次曲线测量 ----------------
function openReading(row) {
  readingForm.value = { dayKey: 'D1', temp: 19, gravity: 12 }
  readingVisible.value = true
}

async function submitReading() {
  await batchApi.putReading(detail.value.id, readingForm.value)
  detail.value = await batchApi.get(detail.value.id)
  ElMessage.success('测量数据已登记（仅影响后续新送检轮次，历史轮次快照不变）')
}

async function removeReading(dayKey) {
  await batchApi.removeReading(detail.value.id, dayKey)
  detail.value = await batchApi.get(detail.value.id)
  ElMessage.success('已删除')
}

onMounted(async () => {
  recipes.value = await recipeApi.list()
  await load()
})
</script>

<style scoped>
.qc-tag { color: #303133; }
.snap-review { color: #c0392b; font-weight: 600; }
.snap-mismatch { color: #b88230; font-weight: 600; border-bottom: 1px dashed #b88230; cursor: help; }
</style>
