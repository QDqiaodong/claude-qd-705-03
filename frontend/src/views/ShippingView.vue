<template>
  <div>
    <div class="panel">
      <div class="panel-title">
        出货与金额汇总
        <span class="hint">待发 → 已发 → 已结款；质检驳回/复检/撤销时未发单冻结，重新放行后恢复</span>
      </div>
      <div class="summary-strip">
        <div class="summary-box"><b>{{ summary.orderTotal || 0 }}</b><span>出货单总数</span></div>
        <div class="summary-box"><b>{{ summary.quantityTotal || 0 }}</b><span>出货总量（件）</span></div>
        <div class="summary-box"><b>￥{{ summary.amountTotal || 0 }}</b><span>出货总额</span></div>
        <div class="summary-box"><b>￥{{ summary.paidAmount || 0 }}</b><span>已结款金额</span></div>
        <div class="summary-box"><b :style="{ color: (summary.frozenCount || 0) > 0 ? '#e6a23c' : '' }">{{ summary.frozenCount || 0 }}</b><span>冻结单</span></div>
        <div v-for="item in summary.items" :key="item.status" class="summary-box">
          <b>{{ item.count }}</b><span>{{ item.statusName }} · ￥{{ item.amount }}</span>
        </div>
      </div>
      <div class="toolbar">
        <el-select v-model="statusFilter" clearable placeholder="按状态筛选" style="width: 170px" @change="load">
          <el-option label="待发" value="PENDING" />
          <el-option label="冻结" value="FROZEN" />
          <el-option label="已发" value="SHIPPED" />
          <el-option label="已结款" value="PAID" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openShipment">新增出货单</el-button>
      </div>
      <el-table :data="shipments" border stripe size="small">
        <el-table-column prop="shipmentNo" label="出货单号" width="130" />
        <el-table-column prop="customer" label="客户" min-width="130" />
        <el-table-column prop="batchNo" label="批次" width="125" />
        <el-table-column label="批次状态" width="90">
          <template #default="{ row }">
            <span v-if="row.batchStatusName" class="tag-inline" style="background:#dfe3e6">{{ row.batchStatusName }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="质检" min-width="150">
          <template #default="{ row }">
            <el-tooltip v-if="row.qcReason" :content="row.qcReason" placement="top" :show-after="150">
              <span class="tag-inline" :style="{ background: qcColor(row.qcStatus) }">
                {{ row.qcStatusName || '待质检' }}<span v-if="row.frozenRoundSeq"> · R{{ row.frozenRoundSeq }}</span>
              </span>
            </el-tooltip>
            <span v-else class="tag-inline" :style="{ background: qcColor(row.qcStatus) }">{{ row.qcStatusName || '待质检' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="packageFormName" label="包装" width="80" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="unitPrice" label="单价" width="80" />
        <el-table-column prop="amount" label="金额" width="100" />
        <el-table-column prop="shipDate" label="出货日期" width="105" />
        <el-table-column label="状态" min-width="200">
          <template #default="{ row }">
            <span class="tag-inline" :style="{ background: shipColor(row.status) }">{{ row.statusName }}</span>
            <div v-if="row.status === 'FROZEN' && row.frozenReason" class="freeze-reason">
              <el-text type="warning" size="12">{{ row.frozenReason }}</el-text>
            </div>
            <div v-else-if="row.blockedReason" class="freeze-reason">
              <el-text type="danger" size="12">{{ row.blockedReason }}</el-text>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-tooltip v-if="row.status === 'FROZEN'" content="冻结单不能推进，需批次重新放行恢复" placement="top">
              <span><el-button size="small" disabled>推进</el-button></span>
            </el-tooltip>
            <el-tooltip v-else-if="row.status === 'PENDING' && row.blockedReason" :content="row.blockedReason" placement="top">
              <span><el-button size="small" disabled>标记已发</el-button></span>
            </el-tooltip>
            <el-button v-else-if="row.nextStatus" size="small" type="success" plain @click="nextStatus(row)">
              {{ row.nextStatusName }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-title">
        灌装单
        <span class="hint">只有熟成阶段、最新一轮质检明确放行且测量齐全的批次才能新建灌装单</span>
      </div>
      <div class="toolbar">
        <span class="spacer"></span>
        <el-button type="success" @click="openBottling">新增灌装单</el-button>
      </div>
      <el-table :data="bottlings" border stripe size="small" max-height="380">
        <el-table-column prop="bottlingNo" label="灌装单号" width="130" />
        <el-table-column prop="batchNo" label="批次" width="125" />
        <el-table-column label="批次状态" width="90">
          <template #default="{ row }">{{ row.batchStatusName || '—' }}</template>
        </el-table-column>
        <el-table-column label="灌装时质检" min-width="140">
          <template #default="{ row }">
            <el-tooltip v-if="row.qcReason" :content="row.qcReason" placement="top" :show-after="150">
              <span class="tag-inline" :style="{ background: qcColor(row.qcStatus) }">{{ row.qcStatusName || '待质检' }}</span>
            </el-tooltip>
            <span v-else class="tag-inline" :style="{ background: qcColor(row.qcStatus) }">{{ row.qcStatusName || '待质检' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="packageFormName" label="包装形式" width="100" />
        <el-table-column prop="quantity" label="数量" width="100" />
        <el-table-column prop="bottleDate" label="灌装日期" width="115" />
        <el-table-column prop="lossL" label="损耗(L)" width="90" />
      </el-table>
    </div>

    <el-dialog v-model="shipVisible" title="新增出货单" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
        title="出货必须基于该批次同包装形式的已有灌装记录，且批次最新一轮质检明确放行；驳回/复检中/已撤销的批次无法选择。" />
      <el-form :model="shipForm" label-width="96px">
        <el-form-item label="出货单号"><el-input v-model="shipForm.shipmentNo" placeholder="如 SH-2026-13" /></el-form-item>
        <el-form-item label="客户"><el-input v-model="shipForm.customer" /></el-form-item>
        <el-form-item label="批次">
          <el-select v-model="shipForm.batchNo" filterable style="width: 100%" placeholder="仅可选择已放行且已灌装的批次">
            <el-option v-for="b in shippableBatches" :key="b.batchNo"
                       :label="`${b.batchNo} · ${b.qcStatusName} · ${b.available} ${pkgName(shipForm.packageForm)}可出`"
                       :value="b.batchNo" />
          </el-select>
          <div v-if="shipForm.batchNo" style="font-size:12px;color:#67c23a;margin-top:4px">
            {{ shipEligibility.hint }}
          </div>
          <div v-else style="font-size:12px;color:#909399;margin-top:4px">
            列表中没有可出货批次时，说明没有批次同时满足“最新放行 + 熟成/已灌装 + 存在该包装的灌装量”
          </div>
        </el-form-item>
        <el-form-item label="包装形式">
          <el-select v-model="shipForm.packageForm" style="width: 100%" @change="shipForm.batchNo = ''">
            <el-option label="瓶" value="BOTTLE" />
            <el-option label="易拉罐" value="CAN" />
            <el-option label="桶" value="KEG" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="shipForm.quantity" :min="1"
                           :max="shipEligibility.available > 0 ? shipEligibility.available : undefined" />
        </el-form-item>
        <el-form-item label="单价"><el-input-number v-model="shipForm.unitPrice" :precision="2" :min="0.01" /></el-form-item>
        <el-form-item label="金额">
          <b>￥{{ (Number(shipForm.quantity) * Number(shipForm.unitPrice)).toFixed(2) }}</b>
        </el-form-item>
        <el-form-item label="出货日期">
          <el-date-picker v-model="shipForm.shipDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipVisible = false">取消</el-button>
        <el-button type="primary" @click="submitShipment">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bottlingVisible" title="新增灌装单" width="480px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
        title="只有熟成/已灌装阶段、最新一轮质检明确放行且测量记录齐全的批次才能灌装。" />
      <el-form :model="bottlingForm" label-width="96px">
        <el-form-item label="灌装单号"><el-input v-model="bottlingForm.bottlingNo" placeholder="如 BT-2026-13" /></el-form-item>
        <el-form-item label="批次">
          <el-select v-model="bottlingForm.batchNo" filterable style="width: 100%" placeholder="仅可选择已放行的熟成批次">
            <el-option v-for="b in bottleableBatches" :key="b.batchNo"
                       :label="`${b.batchNo} · ${b.statusName} · ${b.qcStatusName}`"
                       :value="b.batchNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="包装形式">
          <el-select v-model="bottlingForm.packageForm" style="width: 100%">
            <el-option label="瓶" value="BOTTLE" />
            <el-option label="易拉罐" value="CAN" />
            <el-option label="桶" value="KEG" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量"><el-input-number v-model="bottlingForm.quantity" :min="1" /></el-form-item>
        <el-form-item label="损耗(L)"><el-input-number v-model="bottlingForm.lossL" :precision="2" :min="0" /></el-form-item>
        <el-form-item label="灌装日期">
          <el-date-picker v-model="bottlingForm.bottleDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bottlingVisible = false">取消</el-button>
        <el-button type="primary" @click="submitBottling">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { batchApi, shippingApi } from '../api'

const shipments = ref([])
const bottlings = ref([])
const batches = ref([])
const summary = ref({})
const statusFilter = ref('')
const shipVisible = ref(false)
const bottlingVisible = ref(false)
const shipForm = ref({ shipmentNo: '', customer: '', batchNo: '', packageForm: 'BOTTLE', quantity: 100, unitPrice: 15, shipDate: '' })
const bottlingForm = ref({ bottlingNo: '', batchNo: '', packageForm: 'BOTTLE', quantity: 300, lossL: 5, bottleDate: '' })

const SHIP_COLOR = { PENDING: '#9aa5ab', FROZEN: '#e6a23c', SHIPPED: '#7cb342', PAID: '#5b8a2a' }
const QC_COLORS = {
  NONE: '#c0c4cc', PENDING: '#e6a23c', REINSPECT: '#e6a23c',
  PASS: '#67c23a', REJECT: '#f56c6c', REVOKED: '#909399'
}
function shipColor(s) { return SHIP_COLOR[s] || '#8d9aa5' }
function qcColor(s) { return QC_COLORS[s] || QC_COLORS.NONE }
function pkgName(code) { return { BOTTLE: '瓶', CAN: '罐', KEG: '桶' }[code] || '' }

/** 可灌装：熟成/已灌装 + 最新放行（前端只做提示，真正拦截以后端为准） */
const bottleableBatches = computed(() =>
  (batches.value || []).filter(b =>
    ['CONDITION', 'PACKAGED'].includes(b.status) && b.qcStatus === 'PASS'))

/** 可出货批次候选（选中包装形式后还要看剩余灌装量） */
const shippableBatches = computed(() => {
  return bottleableBatches.value
    .map(b => {
      const bottled = sumBottled(b.batchNo, shipForm.value.packageForm)
      const used = sumShipped(b.batchNo, shipForm.value.packageForm)
      return { ...b, bottled, used, available: bottled - used }
    })
    .filter(b => b.available > 0)
})

const shipEligibility = computed(() => {
  const found = shippableBatches.value.find(b => b.batchNo === shipForm.value.batchNo)
  if (!found) {
    return { available: 0, hint: '' }
  }
  return {
    available: found.available,
    hint: `该批次${pkgName(shipForm.value.packageForm)}装已灌装 ${found.bottled}，待发/冻结/已出占用 ${found.used}，本次最多可出 ${found.available}`
  }
})

function sumBottled(batchNo, pkg) {
  return (bottlings.value || [])
    .filter(x => x.batchNo === batchNo && x.packageForm === pkg)
    .reduce((s, x) => s + Number(x.quantity || 0), 0)
}
function sumShipped(batchNo, pkg) {
  // 所有已登记出货单（含冻结/待发/已发/已结款）都占用灌装量
  return (shipments.value || [])
    .filter(x => x.batchNo === batchNo && x.packageForm === pkg)
    .reduce((s, x) => s + Number(x.quantity || 0), 0)
}

async function load() {
  shipments.value = await shippingApi.shipments(statusFilter.value)
  bottlings.value = await shippingApi.bottlings()
  summary.value = await shippingApi.summary()
  batches.value = await batchApi.list()
}

async function nextStatus(row) {
  await shippingApi.nextStatus(row.id)
  ElMessage.success('已推进到 ' + row.nextStatusName)
  await load()
}

function openShipment() {
  shipForm.value = { shipmentNo: '', customer: '', batchNo: '', packageForm: 'BOTTLE', quantity: 100, unitPrice: 15, shipDate: '' }
  shipVisible.value = true
}

async function submitShipment() {
  if (!shipForm.value.batchNo) { ElMessage.warning('请选择已放行且有灌装记录的批次'); return }
  await shippingApi.createShipment(shipForm.value)
  ElMessage.success('出货单已登记（待发）')
  shipVisible.value = false
  await load()
}

function openBottling() {
  bottlingForm.value = { bottlingNo: '', batchNo: '', packageForm: 'BOTTLE', quantity: 300, lossL: 5, bottleDate: '' }
  bottlingVisible.value = true
}

async function submitBottling() {
  if (!bottlingForm.value.batchNo) { ElMessage.warning('请选择已放行的熟成批次'); return }
  await shippingApi.createBottling(bottlingForm.value)
  ElMessage.success('灌装单已登记')
  bottlingVisible.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.freeze-reason { margin-top: 2px; line-height: 1.3; max-width: 260px; }
</style>
