package co.kuznetsov.mailkick.jmap;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link BlockingQueue} wrapper that silently drops duplicate offers.
 * An ID already present in the queue will not be added again until it is dequeued.
 * Thread-safe: the presence set and the underlying queue are updated atomically via
 * {@link ConcurrentHashMap#putIfAbsent}.
 */
public class DeduplicatingBlockingQueue implements BlockingQueue<String> {

    private final LinkedBlockingQueue<String> delegate = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Boolean> present = new ConcurrentHashMap<>();

    @Override
    public boolean offer(String id) {
        if (present.putIfAbsent(id, Boolean.TRUE) == null) {
            return delegate.offer(id);
        }
        return false;
    }

    @Override
    public String poll(long timeout, TimeUnit unit) throws InterruptedException {
        String id = delegate.poll(timeout, unit);
        if (id != null) {
            present.remove(id);
        }
        return id;
    }

    @Override
    public String poll() {
        String id = delegate.poll();
        if (id != null) {
            present.remove(id);
        }
        return id;
    }

    @Override
    public String take() throws InterruptedException {
        String id = delegate.take();
        present.remove(id);
        return id;
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    // --- remaining BlockingQueue methods delegated straight through ---

    @Override
    public void put(String id) throws InterruptedException {
        if (present.putIfAbsent(id, Boolean.TRUE) == null) {
            delegate.put(id);
        }
    }

    @Override
    public boolean offer(String id, long timeout, TimeUnit unit) throws InterruptedException {
        if (present.putIfAbsent(id, Boolean.TRUE) == null) {
            return delegate.offer(id, timeout, unit);
        }
        return false;
    }

    @Override
    public String peek() {
        return delegate.peek();
    }

    @Override
    public String element() {
        return delegate.element();
    }

    @Override
    public String remove() {
        String id = delegate.remove();
        present.remove(id);
        return id;
    }

    @Override
    public boolean remove(Object o) {
        boolean removed = delegate.remove(o);
        if (removed) {
            present.remove(o);
        }
        return removed;
    }

    @Override
    public boolean add(String id) {
        if (present.putIfAbsent(id, Boolean.TRUE) == null) {
            return delegate.add(id);
        }
        return false;
    }

    @Override
    public int remainingCapacity() {
        return delegate.remainingCapacity();
    }

    @Override
    public int drainTo(Collection<? super String> c) {
        int count = delegate.drainTo(c);
        for (Object id : c) {
            present.remove(id);
        }
        return count;
    }

    @Override
    public int drainTo(Collection<? super String> c, int maxElements) {
        int count = delegate.drainTo(c, maxElements);
        for (Object id : c) {
            present.remove(id);
        }
        return count;
    }

    @Override
    public boolean contains(Object o) {
        return present.containsKey(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends String> c) {
        boolean changed = false;
        for (String id : c) {
            changed |= add(id);
        }
        return changed;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean changed = delegate.removeAll(c);
        if (changed) {
            c.forEach(present::remove);
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        delegate.clear();
        present.clear();
    }

    @Override
    public Iterator<String> iterator() {
        return delegate.iterator();
    }

    @Override
    public Object[] toArray() {
        return delegate.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return delegate.toArray(a);
    }
}