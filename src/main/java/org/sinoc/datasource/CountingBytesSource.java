package org.sinoc.datasource;

import java.util.Arrays;

import org.sinoc.crypto.HashUtil;
import org.sinoc.util.ByteUtil;
import org.sinoc.util.RLP;

/**
 * 'Reference counting' Source. Unlike regular Source if an entry was
 * e.g. 'put' twice it is actually deleted when 'delete' is called twice
 * I.e. each put increments counter and delete decrements counter, the
 * entry is deleted when the counter becomes zero.
 *
 * Please note that the counting mechanism makes sense only for
 * {@link HashedKeySource} like Sources when any taken key can correspond to
 * the only value
 *
 * This Source is constrained to byte[] values only as the counter
 * needs to be encoded to the backing Source value as byte[]
 *
 */
public class CountingBytesSource extends AbstractChainedSource<byte[], byte[], byte[], byte[]>
        implements HashedKeySource<byte[], byte[]> {

    QuotientFilter filter;
    boolean dirty = false;
    private byte[] filterKey = HashUtil.sha3("countingStateFilter".getBytes());

    public CountingBytesSource(Source<byte[], byte[]> src) {
        this(src, false);

    }
    public CountingBytesSource(Source<byte[], byte[]> src, boolean bloom) {
        super(src);
        byte[] filterBytes = src.get(filterKey);
        if (bloom) {
            if (filterBytes != null) {
                filter = QuotientFilter.deserialize(filterBytes);
            } else {
                filter = QuotientFilter.create(5_000_000, 10_000);
            }
        }
    }

    @Override
    public void put(byte[] key, byte[] val) {
        if (val == null) {
            delete(key);
            return;
        }

        synchronized (this) {
            byte[] srcVal = getSource().get(key);
            int srcCount = decodeCount(srcVal);
            if (srcCount >= 1) {
                if (filter != null) filter.insert(key);
                dirty = true;
            }
            getSource().put(key, encodeCount(val, srcCount + 1));
        }
    }

    @Override
    public byte[] get(byte[] key) {
        return decodeValue(getSource().get(key));
    }

    @Override
    public void delete(byte[] key) {
        synchronized (this) {
            int srcCount;
            byte[] srcVal = null;
            if (filter == null || filter.maybeContains(key)) {
                srcVal = getSource().get(key);
                srcCount = decodeCount(srcVal);
            } else {
                srcCount = 1;
            }
            if (srcCount > 1) {
                getSource().put(key, encodeCount(decodeValue(srcVal), srcCount - 1));
            } else {
                getSource().delete(key);
            }
        }
    }

    @Override
    protected boolean flushImpl() {
        if (filter != null && dirty) {
            byte[] filterBytes;
            synchronized (this) {
                filterBytes = filter.serialize();
            }
            getSource().put(filterKey, filterBytes);
            dirty = false;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Extracts value from the backing Source counter + value byte array
     */
    protected byte[] decodeValue(byte[] srcVal) {
        return srcVal == null ? null : Arrays.copyOfRange(srcVal, RLP.decode(srcVal, 0).getPos(), srcVal.length);
    }

    /**
     * Extracts counter from the backing Source counter + value byte array
     */
    protected int decodeCount(byte[] srcVal) {
        return srcVal == null ? 0 : ByteUtil.byteArrayToInt((byte[]) RLP.decode(srcVal, 0).getDecoded());
    }

    /**
     * Composes value and counter into backing Source value
     */
    protected byte[] encodeCount(byte[] val, int count) {
        return ByteUtil.merge(RLP.encodeInt(count), val);
    }
}
