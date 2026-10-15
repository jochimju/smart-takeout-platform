<template>
  <div class="dashboard-container">
    <PageHeader title="秒杀活动" description="选择一个已启售套餐，再配置秒杀价、库存和开抢时间；保存后预热到 Redis。" />
    <div class="container">
      <el-button type="primary" @click="openCreate">
        + 新建秒杀活动
      </el-button>
      <el-table :data="activities" stripe class="tableBox" style="margin-top:16px">
        <el-table-column prop="setmealName" label="套餐" />
        <el-table-column label="原价">
          <template slot-scope="{row}">
            ￥{{ Number(row.originalPrice).toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column label="秒杀价">
          <template slot-scope="{row}">
            <span class="sale">￥{{ Number(row.seckillPrice).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="stock" label="总库存" />
        <el-table-column prop="remainingStock" label="剩余库存" />
        <el-table-column label="库存调整">
          <template slot-scope="{row}">
            <el-button type="text" @click="adjust(row)">
              调整总库存
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="beginTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column label="活动状态" width="110">
          <template slot-scope="{row}">
            <el-tag :type="activityState(row).type" size="mini">
              {{ activityState(row).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290">
          <template slot-scope="{row}">
            <el-button type="text" @click="edit(row)">
              编辑
            </el-button><el-button v-if="canRelaunch(row)" type="text" @click="relaunch(row)">
              再次上架
            </el-button><el-button type="text" @click="warmUp(row.id)">
              预热
            </el-button><el-button type="text" class="delBut" @click="remove(row.id)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <el-dialog :title="dialogTitle" :visible.sync="visible" width="560px" :close-on-click-modal="false">
      <el-form ref="activityForm" :model="form" :rules="rules" label-width="100px">
        <el-alert v-if="mode==='create'" type="info" :closable="false" title="此处创建的是秒杀活动，不会新建套餐。请先在“套餐管理”中新建并启售套餐。" style="margin-bottom:18px" />
        <el-form-item label="参与秒杀的套餐" prop="setmealId">
          <el-select v-model="form.setmealId" :disabled="!!form.id || mode==='relaunch'" filterable placeholder="选择套餐（仅显示已启售）" style="width:100%">
            <el-option v-for="item in setmeals" :key="item.id" :label="`${item.name}（原价￥${item.price}）`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="秒杀价格" prop="seckillPrice">
          <el-input-number v-model="form.seckillPrice" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="总库存" prop="stock">
          <el-input-number v-model="form.stock" :disabled="!!form.id" :min="1" :precision="0" /><div v-if="form.id">
            请在列表中使用“调整总库存”。
          </div>
        </el-form-item>
        <el-form-item label="活动时间" prop="timeRange">
          <el-date-picker v-model="form.timeRange" type="datetimerange" value-format="yyyy-MM-dd HH:mm" start-placeholder="开始" end-placeholder="结束" style="width:100%" />
        </el-form-item>
        <el-form-item label="活动状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-alert v-if="mode==='relaunch'" type="info" :closable="false" title="再次上架会创建新的活动，上一轮已抢购的用户可重新参与。请设置新的库存和活动时间。" />
      </el-form>
      <span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ saveLabel }}</el-button></span>
    </el-dialog>
  </div>
</template>
<script lang="ts">
import { Component, Vue } from 'vue-property-decorator'
import PageHeader from '@/components/PageHeader/index.vue'
import { getSeckillActivities, createSeckillActivity, updateSeckillActivity, deleteSeckillActivity, warmUpSeckillActivity, adjustSeckillStock } from '@/api/seckill'
import { getSetmealPage } from '@/api/setMeal'
@Component({ components: { PageHeader } })
export default class extends Vue {
  activities:any[]=[]; setmeals:any[]=[]; visible=false; saving=false; mode='create'
  form:any={ id:null,setmealId:null,seckillPrice:0,stock:0,status:1,beginTime:'',endTime:'',timeRange:[] }
  rules:any={
    setmealId:[{required:true,message:'请选择已启售套餐',trigger:'change'}],
    seckillPrice:[{required:true,message:'请输入秒杀价格',trigger:'change'}],
    stock:[{required:true,message:'请输入总库存',trigger:'change'}],
    timeRange:[{validator:(_:any,value:any,callback:Function) => value && value.length===2 ? callback() : callback(new Error('请选择活动时间')),trigger:'change'}]
  }
  created(){ this.load(); this.loadSetmeals() }
  async load(){ const r:any=await getSeckillActivities(); this.activities=r.data.data || [] }
  async loadSetmeals(){ const r:any=await getSetmealPage({page:1,pageSize:100,status:1}); this.setmeals=r.data.data.records || [] }
  get dialogTitle(){ return this.mode==='edit' ? '编辑秒杀活动' : (this.mode==='relaunch' ? '再次上架秒杀套餐' : '新建秒杀活动') }
  get saveLabel(){ return this.mode==='relaunch' ? '创建新活动并上架' : (this.mode==='edit' ? '保存配置' : '保存并上架') }
  clearValidation(){ this.$nextTick(() => { const form:any=this.$refs.activityForm; if(form) form.clearValidate() }) }
  openCreate(){ this.mode='create'; this.form={ id:null,setmealId:null,seckillPrice:0,stock:0,status:1,beginTime:'',endTime:'',timeRange:[] }; this.visible=true; this.clearValidation() }
  edit(row:any){ this.mode='edit'; this.form={...row,timeRange:[row.beginTime,row.endTime]}; this.visible=true; this.clearValidation() }
  canRelaunch(row:any){ return row.status !== 1 || Date.now() > new Date(String(row.endTime).replace(/-/g,'/')).getTime() }
  relaunch(row:any){
    this.mode='relaunch'
    this.form={ id:null,setmealId:row.setmealId,seckillPrice:Number(row.seckillPrice),stock:row.stock,status:1,beginTime:'',endTime:'',timeRange:[] }
    this.visible=true
    this.clearValidation()
  }
  activityState(row:any){
    if(row.status !== 1) return {text:'已停用',type:'info'}
    const now=Date.now(); const begin=new Date(String(row.beginTime).replace(/-/g,'/')).getTime(); const end=new Date(String(row.endTime).replace(/-/g,'/')).getTime()
    if(now < begin) return {text:'待开抢',type:'warning'}
    if(now > end) return {text:'已结束',type:'info'}
    return {text:'抢购中',type:'success'}
  }
  selectedSetmeal(){ return this.setmeals.find(item => item.id===this.form.setmealId) }
  async save(){
    const form:any=this.$refs.activityForm
    const valid=await new Promise(resolve => form.validate((ok:boolean) => resolve(ok)))
    if(!valid) return
    const selected:any=this.selectedSetmeal()
    if(selected && Number(this.form.seckillPrice)>=Number(selected.price)){ this.$message.error('秒杀价必须低于套餐原价'); return }
    if(new Date(this.form.timeRange[1]).getTime()<=new Date(this.form.timeRange[0]).getTime()){ this.$message.error('结束时间必须晚于开始时间'); return }
    this.form.beginTime=this.form.timeRange[0]; this.form.endTime=this.form.timeRange[1]; this.saving=true
    try {
      // timeRange 是页面控件字段，后端活动 DTO 只接收 beginTime 和 endTime。
      const payload:any={...this.form}; delete payload.timeRange
      const r:any=this.form.id ? await updateSeckillActivity(payload) : await createSeckillActivity(payload)
      if(r.data.code!==1){ this.$message.error(r.data.msg || '保存失败'); return }
      const activityId=this.form.id || r.data.data
      if(this.form.status===1 && activityId) await warmUpSeckillActivity(activityId)
      this.$message.success('已保存并完成库存预热'); this.visible=false; await this.load()
    } catch(e) {
      const error:any=e
      this.$message.error((error && error.response && error.response.data && error.response.data.msg) || '保存失败，请稍后重试')
    } finally { this.saving=false }
  }
  async warmUp(id:number){ await warmUpSeckillActivity(id); this.$message.success('已预热到 Redis') }
  async adjust(row:any){
    try {
      const input:any=await this.$prompt(`当前总库存 ${row.stock}，已占用 ${row.stock-row.remainingStock}。请输入目标总库存。`,'调整库存',{inputValue:String(row.stock),inputPattern:/^\d+$/,inputErrorMessage:'请输入非负整数'})
      const r:any=await adjustSeckillStock(row.id,Number(input.value),row.stock)
      if(r.data.code===1){ this.$message.success('库存已调整'); this.load() } else this.$message.error(r.data.msg||'调整失败')
    } catch(e){ if(e!=='cancel'&&e!=='close') this.$message.error('调整失败，请刷新后重试') }
  }
  async remove(id:number){
    await this.$confirm('删除后不可恢复，确定继续吗？','提示')
    try {
      const r:any=await deleteSeckillActivity(id)
      if(r.data.code!==1){ this.$message.error(r.data.msg || '删除失败'); return }
      this.$message.success('已删除')
      await this.load()
    } catch(e) {
      if(e!=='cancel'&&e!=='close') this.$message.error('删除失败，请刷新后重试')
    }
  }
}
</script>
<style scoped>.sale{color:#e64545;font-weight:700}</style>
