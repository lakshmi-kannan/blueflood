/*
 * Copyright 2013 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.service;

import com.rackspacecloud.blueflood.io.AstyanaxShardStateIO;
import com.rackspacecloud.blueflood.io.IntegrationTestBase;
import com.rackspacecloud.blueflood.io.ShardStateIO;
import com.rackspacecloud.blueflood.rollup.Granularity;
import com.rackspacecloud.blueflood.utils.Util;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(Parameterized.class)
public class ShardStateIntegrationTest extends IntegrationTestBase {
    
    private ShardStateIO io;
    
    public ShardStateIntegrationTest(ShardStateIO io) {
        this.io = io;    
    }

    @Test
    public void testSingleShardManager() {
        long time = 1234000L;
        Collection<Integer> shards = Lists.newArrayList(1, 2, 3, 4);
        ScheduleContext ctx = new ScheduleContext(time, shards);
        ShardStateWorker pull = new ShardStatePuller(shards, ctx.getShardStateManager(), this.io);
        ShardStateWorker push = new ShardStatePusher(shards, ctx.getShardStateManager(), this.io);
        
        for (long t = time; t < time + 10000000; t += 1000) {
            ctx.update(t + 0, 1);
            ctx.update(t + 2000, 2);
            ctx.update(t + 4000, 3);
            ctx.update(t + 6000, 4);
        }
        
        time += 10000000 + 7;
        ctx.setCurrentTimeMillis(time);
        push.performOperation();
        pull.performOperation();
        
        // the numbers we're testing against are the number of slots per granularity in 10000 seconds.
        for (Granularity g : Granularity.rollupGranularities()) {
            for (int shard : shards) {
                if (g == Granularity.MIN_5)
                    Assert.assertEquals(34, ctx.getSlotStamps(g, shard).size());
                else if (g == Granularity.MIN_20)
                    Assert.assertEquals(9, ctx.getSlotStamps(g, shard).size());
                else if (g == Granularity.MIN_60)
                    Assert.assertEquals(4, ctx.getSlotStamps(g, shard).size());
                else if (g == Granularity.MIN_240)
                    Assert.assertEquals(1, ctx.getSlotStamps(g, shard).size());
                else if (g == Granularity.MIN_1440)
                    Assert.assertEquals(1, ctx.getSlotStamps(g, shard).size());
            }
        }
    }

    @Test
    public void testSetAllCoarserSlotsDirtyForFinerSlot() {
        // Tests that the correct coarser slots are set dirty for a finer slot which was seen out-of-order.
        // Prior to a bug fix, clearFromRunning would throw NPE because we were looking up coarser slots
        // based on the timestamp on the finer slot's UpdateStamp, not based on the relative courser slot from the finer slot
        long time = 1386823200000L;
        final Collection<Integer> shards = Lists.newArrayList(123);
        ScheduleContext ctxA = new ScheduleContext(time, shards);

        ctxA.update(time, 123);
        ShardStateManager.SlotStateManager slotStateManager20 = ctxA.getShardStateManager().getSlotStateManager(123, Granularity.MIN_20);

        UpdateStamp stamp  = slotStateManager20.getSlotStamps().get(518);
        stamp.setTimestamp(time + 3600000L); // add one hour
        ctxA.clearFromRunning("metrics_20m,518,123");
    }

    @Test
    public void testConcurrentShardManagers() {
        long time = 1234000L;
        // notice how they share shard 5.
        final int commonShard = 5;
        final Collection<Integer> shardsA = Lists.newArrayList(1, 2, 3, 4, commonShard);
        final Collection<Integer> shardsB = Lists.newArrayList(11, 22, 33, 44, commonShard);
        Collection<Integer> allShards = new ArrayList<Integer>() {{
            for (int i : Iterables.concat(shardsA, shardsB))
                add(i);
        }};
        
        ScheduleContext ctxA = new ScheduleContext(time, shardsA);
        ScheduleContext ctxB = new ScheduleContext(time, shardsB);

        ShardStateWorker pushA = new ShardStatePusher(allShards, ctxA.getShardStateManager(), this.io);
        ShardStateWorker pullA = new ShardStatePuller(allShards, ctxA.getShardStateManager(), this.io);
        ShardStateWorker pushB = new ShardStatePusher(allShards, ctxB.getShardStateManager(), this.io);
        ShardStateWorker pullB = new ShardStatePuller(allShards, ctxB.getShardStateManager(), this.io);
        
        // send a few updates to all contexts.
        for (ScheduleContext ctx : new ScheduleContext[] { ctxA, ctxB }) {
            for (long t = time; t < time + 10000000; t += 1000) {
                ctx.update(t + 0, 1);
                ctx.update(t + 1000, 11);
                ctx.update(t + 2000, 2);
                ctx.update(t + 3000, 22);
                ctx.update(t + 4000, 3);
                ctx.update(t + 5000, 33);
                ctx.update(t + 6000, 4);
                ctx.update(t + 7000, 44);            
            }
        }
        
        time += 10000000 + 7;
        ctxA.setCurrentTimeMillis(time);
        ctxB.setCurrentTimeMillis(time);
        
        // simulate a poll() cylce for each.
        pushA.performOperation();
        pushB.performOperation();
        pullA.performOperation();
        pullB.performOperation();
        
        // states should be the same.
        for (Granularity g : Granularity.rollupGranularities()) {
            for (int shard : allShards)
                Assert.assertEquals(ctxA.getSlotStamps(g, shard), ctxB.getSlotStamps(g, shard));
        }
        
        time += 300000; // this pushes us forward at least one slot.
        
        // now do this: update ctxA, do 2 push/pull cycles on each state.  they should sill be the same.
        ctxA.update(time,  1);
        ctxA.update(time, 11);
        ctxA.update(time, 2);
        ctxA.update(time, 22);
        ctxA.setCurrentTimeMillis(time);
        ctxB.setCurrentTimeMillis(time);
        
        // states should not be the same in some places.
        Assert.assertFalse(ctxA.getSlotStamps(Granularity.MIN_5, 1).equals(ctxB.getSlotStamps(Granularity.MIN_5, 1)));
        Assert.assertFalse(ctxA.getSlotStamps(Granularity.MIN_5, 11).equals(ctxB.getSlotStamps(Granularity.MIN_5, 11)));
        Assert.assertTrue(ctxA.getSlotStamps(Granularity.MIN_5, 3).equals(ctxB.getSlotStamps(Granularity.MIN_5, 3)));
        Assert.assertTrue(ctxA.getSlotStamps(Granularity.MIN_5, 33).equals(ctxB.getSlotStamps(Granularity.MIN_5, 33)));
        
        // this is where the syncing should happen. Order is important for a valid test.  A contains the updates, so
        // I want to put that one first.  B contains old data and it gets written second.  Part of what I'm verifying
        // is that B doesn't overwrite A with data that is obviously old.
        
        // A pushes updated data
        pushA.performOperation();
        // B tries to push old data (should not overwrite what A just did)
        pushB.performOperation();
        // B pulls new data (should get updates from A).
        pullB.performOperation();
        
        // we didn't do a pull on A because if things are broken, it would have pulled the crap data written by B and
        // given the false impression that all timestamps are the same.
        
        // states should have synced up and be the same again.
        for (Granularity g : Granularity.rollupGranularities()) {
            Assert.assertEquals(ctxA.getSlotStamps(g, commonShard), ctxB.getSlotStamps(g, commonShard));
        }
    }

    @Test
    // this test illustrates how loading shard state clobbered the knowledge that a shard,slot had already been 
    // rolled up.
    public void testUpdateClobbering() {
        long time = 1234L;
        final Collection<Integer> shardsA = Lists.newArrayList(1);
        final Collection<Integer> shardsB = Lists.newArrayList(2);
        Collection<Integer> allShards = new ArrayList<Integer>() {{
            for (int i : Iterables.concat(shardsA, shardsB)) add(i);
        }};
        
        ScheduleContext ctxA = new ScheduleContext(time, shardsA);
        ShardStateWorker pushA = new ShardStatePusher(allShards, ctxA.getShardStateManager(), this.io);
        ShardStateWorker pullA = new ShardStatePuller(allShards, ctxA.getShardStateManager(), this.io);
        
        // update.
        time += 1000;
        ctxA.setCurrentTimeMillis(time);
        ctxA.update(time, 1);
        
        // persist.
        pushA.performOperation();
        
        // time goes on.
        time += 600000;
        ctxA.setCurrentTimeMillis(time);
        
        // should be ready to schedule.
        ctxA.scheduleSlotsOlderThan(300000);
        Assert.assertEquals(1, ctxA.getScheduledCount());
        
        // simulate slots getting run.
        int count = 0;
        while (ctxA.getScheduledCount() > 0) {
            String slot = ctxA.getNextScheduled();
            ctxA.clearFromRunning(slot);
            ctxA.scheduleSlotsOlderThan(300000);
            count += 1;
        }
        Assert.assertEquals(5, count);
        
        // verify that scheduling doesn't find anything else.
        ctxA.scheduleSlotsOlderThan(300000);
        Assert.assertEquals(0, ctxA.getScheduledCount());
        
        // reloading under these circumstances (no updates) should not affect the schedule.
        pullA.performOperation();
        ctxA.scheduleSlotsOlderThan(300000);
        Assert.assertEquals(0, ctxA.getScheduledCount());
    }

    @Test
    public void testShardOperationsConcurrency() throws InterruptedException {
        final long tryFor = 15000;
        final AtomicLong time = new AtomicLong(1234L);
        final Collection<Integer> shards = Collections.unmodifiableCollection(Util.parseShards("ALL"));
        final ScheduleContext ctx = new ScheduleContext(time.get(), shards);
        final CountDownLatch latch = new CountDownLatch(2);
        final Throwable[] errBucket = new Throwable[2];
        Thread pushPull = new Thread() { public void run() {
            ShardStateWorker push = new ShardStatePusher(shards, ctx.getShardStateManager(), ShardStateIntegrationTest.this.io);
            ShardStateWorker pull = new ShardStatePuller(shards, ctx.getShardStateManager(), ShardStateIntegrationTest.this.io);
            
            push.setPeriod(1);
            pull.setPeriod(1);
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < tryFor) {
                try {
                    push.performOperation();
                    pull.performOperation();
                } catch (Throwable th) {
                    th.printStackTrace();
                    errBucket[0] = th;
                    break;
                }
            }
            latch.countDown();
        }};
        Thread updateIterator = new Thread() { public void run() {
            long start = System.currentTimeMillis();
            outer: while (System.currentTimeMillis() - start < tryFor) {
                for (int shard : shards) {
                    time.set(time.get() + 30000);
                    ctx.setCurrentTimeMillis(time.get());
                    try {
                        ctx.update(time.get(), shard);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        errBucket[1] = th;
                        break outer;
                    }
                }
            }
            latch.countDown();
        }};

        pushPull.start();
        updateIterator.start();
        latch.await(tryFor + 2000, TimeUnit.MILLISECONDS);
        Assert.assertNull(errBucket[0]);
        Assert.assertNull(errBucket[1]);
    }
    
    @Parameterized.Parameters
    public static Collection<Object[]> getDifferentShardStateIOInstances() {
        List<Object[]> instances = new ArrayList<Object[]>();
        instances.add(new Object[] { new AstyanaxShardStateIO() });
        instances.add(new Object[] { new InMemoryShardStateIO() });
        return instances;
    }
    
    private static class InMemoryShardStateIO implements ShardStateIO {
        
        private Map<Integer, Map<Granularity, Map<Integer, UpdateStamp>>> map = new HashMap<Integer, Map<Granularity, Map<Integer, UpdateStamp>>>();
        
        @Override
        public Collection<SlotState> getShardState(int shard) throws IOException {
            Map<Granularity, Map<Integer, UpdateStamp>> updates = map.get(shard);
            if (updates == null) {
                return new ArrayList<SlotState>();
            } else {
                List<SlotState> states = new ArrayList<SlotState>();
                for (Map.Entry<Granularity, Map<Integer, UpdateStamp>> e0 : updates.entrySet()) {
                    for (Map.Entry<Integer, UpdateStamp> e1 : e0.getValue().entrySet()) {
                        states.add(new SlotState(e0.getKey(), e1.getKey(), e1.getValue().getState()));
                    }
                }
                return states;
            }
        }

        @Override
        public void putShardState(int shard, Map<Granularity, Map<Integer, UpdateStamp>> slotTimes) throws IOException {
            map.put(shard, slotTimes);
        }
    }
}
