import unittest
from performance_contract import stages,verdict
class ContractTest(unittest.TestCase):
 def test_drops_invalidate_eight_hundred_claim(self):
  r=dict(k6Exit=0,seconds=60,actualCommittedTps=900,remaining=1,redisStock=1,outstanding=0,duplicates=0,raw={'metrics':{'dropped_iterations':{'values':{'count':1}}}})
  self.assertFalse(verdict(r)['actual800TpsPassed']);r['raw']={'metrics':{'http_reqs':{'values':{'count':1000}}}};self.assertTrue(verdict(r)['actual800TpsPassed']);self.assertFalse(verdict(r)['independentClient'])
  r['seconds']=10;self.assertFalse(verdict(r)['actual800TpsPassed'])
 def test_stage_mean_uses_counter_deltas(self):
  a='seckill_profile_seconds_sum{operation="consume",phase="jdbc_commit"} 1\nseckill_profile_seconds_count{operation="consume",phase="jdbc_commit"} 10'
  b='seckill_profile_seconds_sum{operation="consume",phase="jdbc_commit"} 3\nseckill_profile_seconds_count{operation="consume",phase="jdbc_commit"} 30'
  self.assertEqual(stages(a,b)[0]['averageMs'],100)
if __name__=='__main__':unittest.main()
