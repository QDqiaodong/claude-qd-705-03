<template>
  <div>
    <!-- ============ 板块一：维护日历 ============ -->
    <div class="panel">
      <div class="panel-title">
        维护日历
        <span class="hint">维护窗口与占罐/待入罐批次在同一条时间线上；不是给罐加“维护中”状态</span>
        <span class="spacer"></span>
        <el-button type="primary" size="small" @click="openRegister">登记维护窗口</el-button>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>

      <!-- 日历表头：按天刻度 -->
      <div class="cal-row cal-head">
        <div class="cal-tank-cell">发酵罐</div>
        <div class="cal-track">
          <div
            v-for="(d, i) in days"
            :key="i"
            class="cal-day"
            :class="{ 'is-today': d.isToday, 'is-weekend': d.weekend }"
          >
            <span>{{ d.label }}</span>
          </div>
        </div>
      </div>

      <div v-for="row in calendarRows" :key="row.tankNo" class="cal-row">
        <div class="cal-tank-cell">
          <b>{{ row.tankNo }}</b>
          <small>{{ row.capacityL }}L · {{ row.tempRange }}</small>
        </div>
        <div class="cal-track">
          <div
            v-for="(d, i) in days"
            :key="i"
            class="cal-cell"
            :class="{ 'is-today': d.isToday, 'is-weekend': d.weekend }"
          ></div>
          <div
            v-for="w in row.windows"
            :key="w.id"
            class="cal-bar"
            :style="barStyle(w)"
            @click="openTimeline(w)"
          >
            <i :style="{ background: w.statusColor }"></i>
            <span>{{ w.maintTypeName }} · {{ w.statusName }}</span>
          </div>
          <div v-if="!row.windows.length" class="cal-empty-row">—</div>
        </div>
      </div>

      <div class="legend" style="margin-top: 8px">
        <span><i style="background: #e6a23c"></i>计划中</span>
        <span><i style="background: #f56c6c"></i>维护中</span>
        <span><i style="background: #909399"></i>已结束</span>
        <span><i style="background: #c0c4cc"></i>已取消</span>
      </div>
    </div>

    <!-- ============ 板块二：冲突清单 ============ -->
    <div class="panel">
      <div class="panel-title">
        冲突清单
        <span class="hint">无冲突 / 需要迁罐 / 必须延期 / 无法处理 —— 点“查看时间线/迁罐”进入预览</span>
      </div>
      <el-table :data="conflicts" size="small" border>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :style="{ background: row.resultColor, borderColor: row.resultColor, color: '#fff' }">
              {{ row.kindName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批号" width="130" />
        <el-table-column prop="stageName" label="当前阶段" width="90" />
        <el-table-column label="罐 / 产量" width="120">
          <template #default="{ row }">{{ row.tankNo }} · {{ row.volumeL }}L</template>
        </el-table-column>
        <el-table-column label="占罐/入罐时段" width="260">
          <template #default="{ row }">{{ fmtTime(row.occupyFrom || row.plannedEnterAt) }} ~ {{ fmtTime(row.occupyTo) }}</template>
        </el-table-column>
        <el-table-column label="结论" width="110">
          <template #default="{ row }">
            <b :style="{ color: row.resultColor }">{{ row.resultName }}</b>
          </template>
        </el-table-column>
        <el-table-column prop="resultDetail" label="说明" min-width="280" show-overflow-overline />
        <el-table-column label="方案" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.planId" size="small" :type="planTagType(row.planStatus)">
              #{{ row.planId }} {{ row.planStatusName }}{{ row.targetTankNo ? ' → ' + row.targetTankNo : '' }}
            </el-tag>
            <span v-else class="hint">未生成</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.kind === 'OCCUPYING' && row.maintenanceId"
              size="small"
              type="primary"
              plain
              @click="openTimelineById(row.maintenanceId)"
            >时间线/迁罐</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!conflicts.length" class="empty-tip">当前没有任何冲突 —— 所有维护窗口与排程无冲突</div>
    </div>

    <!-- ============ 板块三 & 四：时间线 + 迁罐预览 ============ -->
    <el-drawer v-model="timelineVisible" :title="'维护时间线 · ' + (timeline ? timeline.maintenance.tankNo : '')" size="720px">
      <div v-if="timeline">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="类型">{{ timeline.maintenance.maintTypeName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :style="{ background: timeline.maintenance.statusColor, borderColor: timeline.maintenance.statusColor, color: '#fff' }">
              {{ timeline.maintenance.statusName }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="开始">{{ fmtTime(timeline.maintenance.startAt) }}</el-descriptions-item>
          <el-descriptions-item label="预计恢复">{{ fmtTime(timeline.maintenance.expectedRecoverAt) }}</el-descriptions-item>
          <el-descriptions-item label="实际恢复">{{ fmtTime(timeline.maintenance.actualRecoverAt) }}</el-descriptions-item>
          <el-descriptions-item label="登记人">{{ timeline.maintenance.createdBy }}</el-descriptions-item>
          <el-descriptions-item label="原因" :span="2">{{ timeline.maintenance.reason }}</el-descriptions-item>
          <el-descriptions-item label="影响范围" :span="2">{{ timeline.maintenance.impactScope || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="summary-strip">
          <div class="summary-box"><b :style="{ color: timeline.overallResultColor }">{{ timeline.overallResultName }}</b><span>汇总结论</span></div>
          <div class="summary-box"><b>{{ timeline.items.length }}</b><span>受影响批次</span></div>
          <div class="summary-box"><b>{{ timeline.maintenance.version }}</b><span>当前版本</span></div>
        </div>

        <!-- 操作按钮 -->
        <div style="margin: 8px 0 12px">
          <el-button v-if="timeline.maintenance.startable" size="small" type="warning" plain @click="actStart">标记开始</el-button>
          <el-button v-if="timeline.maintenance.finishable" size="small" type="success" plain @click="actFinish">提前结束</el-button>
          <el-button v-if="timeline.maintenance.editable" size="small" plain @click="openAdjust">改期/延期</el-button>
          <el-button v-if="timeline.maintenance.cancellable" size="small" type="danger" plain @click="actCancel">取消窗口</el-button>
        </div>

        <!-- 同一条时间线 -->
        <div class="panel-title" style="margin-top: 4px">同条时间线上的批次</div>
        <div v-for="it in timeline.items" :key="it.kind + it.batchId" class="conflict-card">
          <div class="conflict-head">
            <el-tag size="small">{{ it.kindName }}</el-tag>
            <b>{{ it.batchNo }}</b>
            <span class="hint">{{ it.stageName }} · {{ it.volumeL }}L · 罐 {{ it.tankNo || '—' }}</span>
            <span class="spacer"></span>
            <el-tag size="small" :style="{ background: it.resultColor, borderColor: it.resultColor, color: '#fff' }">
              {{ it.resultName }}
            </el-tag>
          </div>
          <div class="hint" style="margin: 4px 0">
            时段：{{ fmtTime(it.occupyFrom || it.plannedEnterAt) }} ~ {{ fmtTime(it.occupyTo) }}
          </div>
          <div style="margin: 4px 0">{{ it.resultDetail }}</div>

          <!-- 迁罐预览：候选目标罐逐条列适配情况 -->
          <template v-if="it.candidates && it.candidates.length">
            <el-table :data="it.candidates" size="small" border style="margin: 6px 0">
              <el-table-column prop="tankNo" label="目标罐" width="90" />
              <el-table-column label="罐容/温区" width="160">
                <template #default="{ row }">{{ row.capacityL }}L · {{ row.tempMin }}~{{ row.tempMax }}℃</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <span :style="{ color: row.idle ? '#67c23a' : '#f56c6c' }">
                    {{ row.idle ? '空闲' : row.tankStatusName }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="容量" width="64">
                <template #default="{ row }">
                  <span :style="{ color: row.capacityOk ? '#67c23a' : '#f56c6c' }">{{ row.capacityOk ? '✓' : '✗' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="温区" width="64">
                <template #default="{ row }">
                  <span :style="{ color: row.tempCompatible ? '#67c23a' : '#f56c6c' }">{{ row.tempCompatible ? '✓' : '✗' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="无维护" width="70">
                <template #default="{ row }">
                  <span :style="{ color: row.noMaintenance ? '#67c23a' : '#f56c6c' }">{{ row.noMaintenance ? '✓' : '✗' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="未预留" width="70">
                <template #default="{ row }">
                  <span :style="{ color: row.notReserved ? '#67c23a' : '#f56c6c' }">{{ row.notReserved ? '✓' : '✗' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="不适配原因" min-width="200">
                <template #default="{ row }">
                  <span v-if="row.suitable" style="color: #67c23a">全部满足，可迁入</span>
                  <span v-else style="color: #f56c6c">{{ row.mismatchReasons.join('；') }}</span>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <div style="margin-top: 6px">
            <el-button
              v-if="it.kind === 'OCCUPYING' && (!it.planStatus || it.planStatus === 'EXPIRED' || it.planStatus === 'CANCELLED')"
              size="small" type="primary" @click="propose(it)"
            >{{ it.planId ? '重新计算方案' : '生成迁罐方案（自动选罐）' }}</el-button>
            <el-button
              v-if="it.planStatus === 'PENDING' && it.failedStep"
              size="small" type="warning" @click="openPlan(it.planId)"
            >迁移失败待处理（#{{ it.planId }}）</el-button>
            <el-button
              v-else-if="it.planId"
              size="small" :type="it.planStatus === 'EXECUTED' ? 'success' : 'primary'" plain
              @click="openPlan(it.planId)"
            >#{{ it.planId }} {{ it.planStatusName }}</el-button>
          </div>
        </div>
        <div v-if="!timeline.items.length" class="empty-tip">该窗口与任何批次都无冲突</div>

        <!-- 维护操作历史 -->
        <div class="panel-title" style="margin-top: 14px">维护操作历史（只追加，不可删除）</div>
        <el-timeline>
          <el-timeline-item v-for="e in timeline.maintenance.events" :key="e.id" :timestamp="fmtTime(e.createdAt)" placement="top">
            <b>{{ maintEventName(e.eventType) }}</b> · {{ e.operator }}
            <div v-if="e.reason" class="hint">{{ e.reason }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- ============ 迁罐方案：处理进度 ============ -->
    <el-drawer v-model="planVisible" title="迁罐预览与处理进度" size="640px">
      <div v-if="plan">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 10px">
          <el-descriptions-item label="批号">{{ plan.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="阶段">{{ plan.batchStageName }}（迁移不重置）</el-descriptions-item>
          <el-descriptions-item label="原罐">{{ plan.sourceTankNo }}</el-descriptions-item>
          <el-descriptions-item label="目标罐">{{ plan.targetTankNo }}</el-descriptions-item>
          <el-descriptions-item label="产量">{{ plan.batchVolumeL }}L</el-descriptions-item>
          <el-descriptions-item label="批次所需温区">{{ plan.needTempMin }}~{{ plan.needTempMax }}℃</el-descriptions-item>
          <el-descriptions-item label="预计迁移" :span="2">{{ fmtTime(plan.plannedMoveAt) }}</el-descriptions-item>
          <el-descriptions-item label="状态" :span="2">
            <el-tag size="small" :type="planTagType(plan.status)">{{ plan.statusName }}</el-tag>
            <span class="hint"> · 第 {{ plan.attemptCount }} 轮尝试 · 版本 {{ plan.version }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="plan.failReason" label="最近失败" :span="2">
            <span style="color: #f56c6c">第 {{ plan.failedStep }} 步：{{ plan.failReason }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="plan.expiredReason" label="过期原因" :span="2">
            <span style="color: #e6a23c">{{ plan.expiredReason }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="!plan.stillValid && (plan.status === 'PENDING' || plan.status === 'EXECUTING')"
          type="error" :closable="false" show-icon style="margin-bottom: 10px"
          :title="'方案已过期：' + plan.mismatchReasons.join('；') + '，请重新计算方案'"
        />

        <!-- 五步处理进度（按尝试分组） -->
        <div class="panel-title">处理进度（断管 → 排空 → 吊运 → 复容 → 接管）</div>
        <div v-for="attempt in groupedSteps" :key="attempt.seq" class="attempt-block">
          <div class="hint">第 {{ attempt.seq }} 轮尝试</div>
          <div class="step-line">
            <div
              v-for="s in attempt.steps"
              :key="s.id"
              class="step-node"
              :class="'st-' + s.status"
            >
              <b>{{ s.stepSeq }}</b>
              <span>{{ s.stepName }}</span>
              <small>{{ stepStatusName(s.status) }}</small>
            </div>
          </div>
        </div>

        <!-- 操作按钮 -->
        <div style="margin: 12px 0">
          <el-button
            v-if="plan.status === 'PENDING' && !plan.failedStep && plan.stillValid"
            type="primary" @click="confirmPlan"
          >确认迁罐（按最新条件复核）</el-button>
          <el-button
            v-if="plan.status === 'EXECUTING'"
            type="success" @click="doNextStep"
          >完成第 {{ plan.completedStep + 1 }} 步：{{ nextStepName }}</el-button>
          <el-button
            v-if="plan.status === 'EXECUTING'"
            type="danger" plain @click="failDialog = true"
          >中途失败上报</el-button>
          <el-button
            v-if="plan.status === 'PENDING' && plan.failedStep && plan.stillValid"
            type="warning" @click="resumePlan"
          >继续处理（重开五步）</el-button>
          <el-button v-if="plan.status === 'PENDING'" plain @click="cancelPlanDialog = true">取消方案</el-button>
        </div>

        <!-- 操作流水 -->
        <div class="panel-title" style="margin-top: 8px">迁罐操作流水（只追加）</div>
        <el-timeline>
          <el-timeline-item v-for="e in plan.events" :key="e.id" :timestamp="fmtTime(e.createdAt)" placement="top">
            <b>{{ transferEventName(e.eventType) }}</b>
            <span v-if="e.stepSeq != null"> · 第 {{ e.stepSeq }} 步 · 第 {{ e.attemptSeq }} 轮</span>
            · {{ e.operator }}
            <div v-if="e.detail" class="hint">{{ e.fromTankNo }} → {{ e.toTankNo }}：{{ e.detail }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 登记窗口 -->
    <el-dialog v-model="registerVisible" title="登记维护窗口" width="520px">
      <el-form :model="form" label-width="104px">
        <el-form-item label="发酵罐">
          <el-select v-model="form.tankNo" style="width: 100%" placeholder="选择发酵罐">
            <el-option v-for="t in tanks" :key="t.id" :label="t.tankNo + '（' + t.capacityL + 'L，' + t.statusName + '）'" :value="t.tankNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.maintType">
            <el-radio value="PLANNED">计划维护</el-radio>
            <el-radio value="EMERGENCY">突发停机</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="form.startAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="预计恢复">
          <el-date-picker v-model="form.expectedRecoverAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="停机原因" />
        </el-form-item>
        <el-form-item label="影响范围">
          <el-input v-model="form.impactScope" type="textarea" :rows="2" placeholder="影响在罐批次 / 待入罐计划" />
        </el-form-item>
        <el-form-item label="登记人">
          <el-input v-model="form.operator" placeholder="设备负责人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRegister">保存并计算冲突</el-button>
      </template>
    </el-dialog>

    <!-- 改期/延期 -->
    <el-dialog v-model="adjustVisible" title="改期 / 延期" width="480px">
      <el-form label-width="104px" v-if="timeline">
        <el-form-item label="开始时间">
          <el-date-picker
            v-model="adjustForm.startAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%" :disabled="timeline.maintenance.status === 'IN_PROGRESS'"
          />
        </el-form-item>
        <el-form-item label="新预计恢复">
          <el-date-picker v-model="adjustForm.expectedRecoverAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="变更原因"><el-input v-model="adjustForm.reason" /></el-form-item>
        <el-form-item label="操作人"><el-input v-model="adjustForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAdjust">提交（旧方案将过期）</el-button>
      </template>
    </el-dialog>

    <!-- 中途失败上报 -->
    <el-dialog v-model="failDialog" title="迁移中途失败" width="420px">
      <el-form label-width="92px" v-if="plan">
        <el-form-item label="失败步骤">
          <el-select v-model="failForm.failedStep">
            <el-option
              v-for="n in Math.min(plan.completedStep + 1, 5)"
              :key="n" :label="`第 ${n} 步：${stepNameOf(n)}`" :value="n"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="失败原因"><el-input v-model="failForm.reason" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="操作人"><el-input v-model="failForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="failDialog = false">取消</el-button>
        <el-button type="danger" @click="submitFail">回退到待处理</el-button>
      </template>
    </el-dialog>

    <!-- 取消方案 -->
    <el-dialog v-model="cancelPlanDialog" title="取消迁罐方案" width="420px">
      <el-form label-width="92px">
        <el-form-item label="取消原因"><el-input v-model="cancelForm.reason" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="操作人"><el-input v-model="cancelForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelPlanDialog = false">返回</el-button>
        <el-button type="danger" @click="submitCancelPlan">确认取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fermenterApi, maintenanceApi } from '../api'

const OPERATOR = '设备-老郑'
const DAY_MS = 24 * 3600 * 1000

const windows = ref([])
const conflicts = ref([])
const tanks = ref([])
const timelineVisible = ref(false)
const timeline = ref(null)
const planVisible = ref(false)
const plan = ref(null)
const registerVisible = ref(false)
const adjustVisible = ref(false)
const failDialog = ref(false)
const cancelPlanDialog = ref(false)

const form = ref(emptyForm())
const adjustForm = ref({ startAt: null, expectedRecoverAt: null, reason: '', operator: OPERATOR, expectedVersion: 0 })
const failForm = ref({ failedStep: 1, reason: '', operator: OPERATOR })
const cancelForm = ref({ reason: '', operator: OPERATOR })

function emptyForm() {
  return { tankNo: '', maintType: 'PLANNED', startAt: null, expectedRecoverAt: null, reason: '', impactScope: '', operator: OPERATOR }
}

// -------- 日历 --------
const rangeStart = new Date(new Date().setHours(0, 0, 0, 0)).getTime() - 2 * DAY_MS
const rangeEnd = rangeStart + 10 * DAY_MS

const days = computed(() => {
  const arr = []
  const todayStr = new Date().toDateString()
  for (let t = rangeStart; t < rangeEnd; t += DAY_MS) {
    const d = new Date(t)
    arr.push({
      t,
      label: `${d.getMonth() + 1}/${d.getDate()}`,
      isToday: d.toDateString() === todayStr,
      weekend: d.getDay() === 0 || d.getDay() === 6
    })
  }
  return arr
})

const calendarRows = computed(() => {
  const byTank = new Map()
  for (const t of tanks.value) {
    byTank.set(t.tankNo, {
      tankNo: t.tankNo,
      capacityL: t.capacityL,
      tempRange: `${t.tempMin ?? '?'}~${t.tempMax ?? '?'}℃`,
      windows: []
    })
  }
  for (const w of windows.value) {
    if (!byTank.has(w.tankNo)) continue
    byTank.get(w.tankNo).windows.push(w)
  }
  return [...byTank.values()].sort((a, b) => a.tankNo.localeCompare(b.tankNo))
})

function barStyle(w) {
  const s = Math.max(new Date(w.startAt).getTime(), rangeStart)
  const e = Math.min(new Date(w.expectedRecoverAt).getTime(), rangeEnd)
  const left = ((s - rangeStart) / (rangeEnd - rangeStart)) * 100
  const width = Math.max(((e - s) / (rangeEnd - rangeStart)) * 100, 1.5)
  const dim = w.status === 'CANCELLED' ? 0.45 : 1
  return { left: left + '%', width: width + '%', opacity: dim }
}

// -------- 数据加载 --------
async function loadAll() {
  windows.value = await maintenanceApi.windows()
  conflicts.value = await maintenanceApi.conflicts()
  tanks.value = await fermenterApi.list()
}

// -------- 时间线 --------
async function openTimeline(w) {
  await loadTimeline(w.id)
  timelineVisible.value = true
}
async function openTimelineById(id) {
  if (!id) return
  await loadTimeline(id)
  timelineVisible.value = true
}
async function loadTimeline(id) {
  timeline.value = await maintenanceApi.timeline(id)
}

// -------- 维护操作 --------
function openRegister() {
  form.value = emptyForm()
  registerVisible.value = true
}
async function submitRegister() {
  await maintenanceApi.register(form.value)
  ElMessage.success('维护窗口已登记，冲突已计算')
  registerVisible.value = false
  await loadAll()
}

function openAdjust() {
  const m = timeline.value.maintenance
  adjustForm.value = {
    startAt: m.startAt,
    expectedRecoverAt: m.expectedRecoverAt,
    reason: '',
    operator: OPERATOR,
    expectedVersion: m.version
  }
  adjustVisible.value = true
}
async function submitAdjust() {
  await maintenanceApi.adjust(timeline.value.maintenance.id, adjustForm.value)
  ElMessage.success('窗口已更新，关联的旧迁罐方案已过期')
  adjustVisible.value = false
  await loadAll()
  await loadTimeline(timeline.value.maintenance.id)
}

async function actStart() {
  const m = timeline.value.maintenance
  const { value } = await ElMessageBox.prompt('请输入操作人', '标记维护开始', { inputValue: OPERATOR }).catch(() => ({}))
  if (!value) return
  await maintenanceApi.start(m.id, { operator: value, expectedVersion: m.version })
  ElMessage.success('维护已开始')
  await loadAll()
  await loadTimeline(m.id)
}
async function actFinish() {
  const m = timeline.value.maintenance
  const { value } = await ElMessageBox.prompt('提前结束说明', '提前结束维护', { inputValue: '维护提前完成' }).catch(() => ({}))
  if (value === undefined) return
  await maintenanceApi.finish(m.id, { operator: OPERATOR, reason: value, expectedVersion: m.version })
  ElMessage.success('维护已结束，后续排程按新窗口更新（已发生迁罐历史不变）')
  await loadAll()
  await loadTimeline(m.id)
}
async function actCancel() {
  const m = timeline.value.maintenance
  const { value } = await ElMessageBox.prompt('取消原因（已开始的维护不能取消）', '取消维护窗口', {
    inputValue: '计划取消'
  }).catch(() => ({}))
  if (!value) return
  await maintenanceApi.cancel(m.id, { reason: value, operator: OPERATOR, expectedVersion: m.version })
  ElMessage.success('窗口已取消，待处理迁罐方案已过期')
  await loadAll()
  await loadTimeline(m.id)
}

// -------- 迁罐方案 --------
async function propose(item) {
  try {
    const p = await maintenanceApi.propose(timeline.value.maintenance.id, item.batchId, { operator: OPERATOR })
    ElMessage.success(`方案 #${p.id} 已生成：${p.sourceTankNo} → ${p.targetTankNo}，待确认`)
    await loadAll()
    await loadTimeline(timeline.value.maintenance.id)
    await openPlan(p.id)
  } catch (e) {
    // 拦截器已弹错误提示
  }
}

async function openPlan(id) {
  plan.value = await maintenanceApi.plan(id)
  failForm.value.failedStep = Math.min((plan.value.completedStep || 0) + 1, 5)
  failForm.value.reason = ''
  planVisible.value = true
}

async function refreshPlan() {
  plan.value = await maintenanceApi.plan(plan.value.id)
}

async function confirmPlan() {
  try {
    await maintenanceApi.confirm(plan.value.id, {
      planId: plan.value.id, operator: OPERATOR, expectedVersion: plan.value.version
    })
    ElMessage.success('已按最新条件复核通过，进入五步迁移')
    await refreshPlan()
    await loadAll()
    await loadTimeline(timeline.value.maintenance.id)
  } catch (e) {
    await refreshPlan()
    await loadAll()
  }
}

const nextStepName = computed(() => plan.value ? stepNameOf(plan.value.completedStep + 1) : '')

async function doNextStep() {
  await maintenanceApi.completeStep(plan.value.id, plan.value.completedStep + 1, OPERATOR)
  ElMessage.success('步骤已完成')
  await refreshPlan()
  if (plan.value.status === 'EXECUTED') {
    ElMessage.success('迁罐执行完成：曲线/阶段已保留，历史罐号已追加')
    await loadAll()
    if (timeline.value) await loadTimeline(timeline.value.maintenance.id)
  }
}

async function submitFail() {
  await maintenanceApi.fail(plan.value.id, {
    planId: plan.value.id,
    failedStep: failForm.value.failedStep,
    reason: failForm.value.reason,
    operator: failForm.value.operator || OPERATOR,
    expectedVersion: plan.value.version
  })
  ElMessage.warning('已回退到待处理：原罐继续占用、目标罐保持空闲')
  failDialog.value = false
  await refreshPlan()
  await loadAll()
}

async function resumePlan() {
  await maintenanceApi.resume(plan.value.id, {
    operator: OPERATOR, expectedVersion: plan.value.version
  })
  ElMessage.success('已按最新条件复核，重开五步迁移')
  await refreshPlan()
}

async function submitCancelPlan() {
  await maintenanceApi.cancelPlan(plan.value.id, {
    reason: cancelForm.value.reason, operator: cancelForm.value.operator || OPERATOR
  })
  ElMessage.success('方案已取消')
  cancelPlanDialog.value = false
  await refreshPlan()
  await loadAll()
}

// -------- 展示辅助 --------
const STEP_NAMES = ['断管隔离', '排空原罐', '吊运转罐', '目标罐复容', '接管温控']
function stepNameOf(n) {
  return STEP_NAMES[n - 1] || ''
}
const groupedSteps = computed(() => {
  if (!plan.value) return []
  const map = new Map()
  for (const s of plan.value.steps) {
    if (!map.has(s.attemptSeq)) map.set(s.attemptSeq, { seq: s.attemptSeq, steps: [] })
    map.get(s.attemptSeq).steps.push(s)
  }
  return [...map.values()]
})
function stepStatusName(s) {
  return { PENDING: '待执行', DONE: '已完成', FAILED: '失败', ROLLED_BACK: '已回退' }[s] || s
}
function planTagType(s) {
  return { PENDING: 'warning', EXECUTING: 'primary', EXECUTED: 'success', EXPIRED: 'info', CANCELLED: 'info' }[s] || ''
}
function maintEventName(t) {
  return { REGISTER: '登记', RESCHEDULE: '改期', CANCEL: '取消', START: '开始', FINISH_EARLY: '提前结束', EXTEND: '延期' }[t] || t
}
function transferEventName(t) {
  return {
    PROPOSE: '生成方案', CONFIRM: '确认', STEP_DONE: '步骤完成', STEP_FAIL: '步骤失败',
    ROLLBACK: '失败回退', RESUME: '继续处理', EXECUTE: '执行完成', EXPIRE: '方案过期', CANCEL: '取消方案'
  }[t] || t
}
function fmtTime(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}

onMounted(loadAll)
</script>

<style scoped>
.cal-row {
  display: flex;
  align-items: stretch;
  border-bottom: 1px dashed #e3e8d9;
  min-height: 38px;
}
.cal-head { min-height: 28px; font-size: 12px; color: #6b7760; }
.cal-tank-cell {
  width: 130px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 4px 8px;
  border-right: 1px solid #e3e8d9;
}
.cal-tank-cell small { color: #8a9482; font-size: 11px; }
.cal-track {
  position: relative;
  flex: 1;
  display: flex;
}
.cal-day, .cal-cell {
  flex: 1;
  border-right: 1px dotted #edf1e6;
  font-size: 11px;
  text-align: center;
  padding-top: 4px;
  color: #8a9482;
}
.cal-cell.is-today, .cal-day.is-today {
  background: rgba(230, 162, 60, 0.12);
}
.cal-cell.is-weekend, .cal-day.is-weekend {
  background: rgba(144, 147, 153, 0.06);
}
.cal-bar {
  position: absolute;
  top: 6px;
  height: 24px;
  border-radius: 4px;
  background: #fdf6ec;
  border: 1px solid #e6a23c;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 6px;
  font-size: 11px;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
}
.cal-bar i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}
.cal-empty-row {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  color: #c0c4cc;
  font-size: 12px;
}
.conflict-card {
  border: 1px solid #e3e8d9;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fcfdf9;
}
.conflict-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.attempt-block {
  margin: 8px 0;
  padding: 8px;
  border: 1px dashed #d7dec8;
  border-radius: 8px;
  background: #fcfdf9;
}
.step-line {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}
.step-node {
  flex: 1;
  border-radius: 8px;
  padding: 8px 4px;
  text-align: center;
  border: 1px solid #d7dec8;
  background: #fff;
}
.step-node b {
  display: inline-flex;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #c0c4cc;
  color: #fff;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}
.step-node span {
  display: block;
  font-size: 12px;
  margin-top: 2px;
}
.step-node small {
  display: block;
  color: #8a9482;
  font-size: 11px;
}
.st-DONE b { background: #67c23a; }
.st-DONE { border-color: #67c23a; }
.st-FAILED b { background: #f56c6c; }
.st-FAILED { border-color: #f56c6c; background: #fef0f0; }
.st-ROLLED_BACK b { background: #e6a23c; }
.st-ROLLED_BACK { border-color: #e6a23c; background: #fdf6ec; }
.st-PENDING b { background: #c0c4cc; }
</style>
