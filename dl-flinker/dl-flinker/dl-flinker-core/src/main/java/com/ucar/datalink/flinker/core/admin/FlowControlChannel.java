package com.ucar.datalink.flinker.core.admin;

import com.ucar.datalink.flinker.api.element.Record;
import com.ucar.datalink.flinker.api.exception.DataXException;
import com.ucar.datalink.flinker.api.util.Configuration;
import com.ucar.datalink.flinker.core.statistics.communication.Communication;
import com.ucar.datalink.flinker.core.statistics.communication.CommunicationTool;
import com.ucar.datalink.flinker.core.transport.record.TerminateRecord;
import com.ucar.datalink.flinker.core.util.FrameworkErrorCode;
import com.ucar.datalink.flinker.core.util.container.CoreConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by user on 2018/2/5.
 */
public class FlowControlChannel extends ChannelBase {

    private static final Logger LOG = LoggerFactory.getLogger(FlowControlChannel.class);

    private int bufferSize = 0;

    private AtomicInteger memoryBytes = new AtomicInteger(0);

    private ArrayBlockingQueue<Record> queue = null;

    private ReentrantLock lock;

    private Condition notInsufficient, notEmpty;

    private Communication lastCommunication = new Communication();



    public FlowControlChannel(final Configuration configuration) {
        super(configuration);
        this.queue = new ArrayBlockingQueue<Record>(this.getCapacity());
        this.bufferSize = configuration.getInt(CoreConstant.DATAX_CORE_TRANSPORT_EXCHANGER_BUFFERSIZE);

        lock = new ReentrantLock();
        notInsufficient = lock.newCondition();
        notEmpty = lock.newCondition();
    }

    @Override
    public void close() {
        super.close();
        try {
            this.queue.put(TerminateRecord.get());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void clear(){
        this.queue.clear();
    }

    @Override
    protected void doPush(Record r) {
        try {
            long startTime = System.nanoTime();
            this.queue.put(r);
            waitWriterTime += System.nanoTime() - startTime;
            memoryBytes.addAndGet(r.getMemorySize());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void doPushAll(Collection<Record> rs) {
        try {
            long startTime = System.nanoTime();
            lock.lockInterruptibly();
            int bytes = getRecordBytes(rs);
            while (memoryBytes.get() + bytes > this.byteCapacity || rs.size() > this.queue.remainingCapacity()) {
                notInsufficient.await(200L, TimeUnit.MILLISECONDS);
            }
            this.queue.addAll(rs);
            waitWriterTime += System.nanoTime() - startTime;
            memoryBytes.addAndGet(bytes);
            notEmpty.signalAll();
        } catch (InterruptedException e) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.RUNTIME_ERROR, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected Record doPull() {
        try {
            long startTime = System.nanoTime();
            Record r = this.queue.take();
            waitReaderTime += System.nanoTime() - startTime;
            memoryBytes.addAndGet(-r.getMemorySize());
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void doPullAll(Collection<Record> rs) {
        assert rs != null;
        rs.clear();
        try {
            long startTime = System.nanoTime();
            lock.lockInterruptibly();
            while (this.queue.drainTo(rs, bufferSize) <= 0) {
                notEmpty.await(200L, TimeUnit.MILLISECONDS);
            }
            waitReaderTime += System.nanoTime() - startTime;
            int bytes = getRecordBytes(rs);
            memoryBytes.addAndGet(-bytes);
            notInsufficient.signalAll();
        } catch (InterruptedException e) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.RUNTIME_ERROR, e);
        } finally {
            lock.unlock();
        }
    }

    private int getRecordBytes(Collection<Record> rs){
        int bytes = 0;
        for(Record r : rs){
            bytes += r.getMemorySize();
        }
        return bytes;
    }

    @Override
    public int size() {
        return this.queue.size();
    }

    @Override
    public boolean isEmpty() {
        return this.queue.isEmpty();
    }





}
