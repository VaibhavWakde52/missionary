package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public interface Propagator {

    class Publisher extends AFn implements Comparable<Publisher> {
        static {
            Util.printDefault(Publisher.class);
        }

        final int[] ranks;
        final Object initp;
        final Object inits;
        final IFn perform;
        final IFn subscribe;
        final IFn lcb;
        final IFn rcb;
        final IFn tick;
        final IFn accept;
        final IFn reject;

        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger children = new AtomicInteger();

        IFn effect;
        Process current;
        Publisher child;
        Publisher sibling;
        Subscription prop;

        Publisher(int[] ranks, Object initp, Object inits, IFn perform, IFn subscribe,
                  IFn lcb, IFn rcb, IFn tick, IFn accept, IFn reject, IFn effect) {
            this.ranks = ranks;
            this.initp = initp;
            this.inits = inits;
            this.perform = perform;
            this.subscribe = subscribe;
            this.lcb = lcb;
            this.rcb = rcb;
            this.tick = tick;
            this.accept = accept;
            this.reject = reject;
            this.effect = effect;
        }

        @Override
        public Object invoke(Object l, Object r) {
            return sub(this, (IFn) l, (IFn) r);
        }

        @Override
        public int compareTo(Publisher that) {
            return this == that ? 0 : lt(this.ranks, that.ranks) ? -1 : 1;
        }
    }

    class Process {
        final Publisher parent;

        Object state;
        Object process;
        Subscription waiting;
        Subscription pending;

        Process(Publisher parent) {
            this.parent = parent;
        }
    }

    class Subscription extends AFn implements IDeref {
        static {
            Util.printDefault(Subscription.class);
        }

        final Process source;
        final Process target;
        final IFn lcb;
        final IFn rcb;

        Subscription prev;
        Subscription next;
        Subscription prop;

        Object state;
        boolean flag;          // if task : success. if flow : pending

        Subscription(Process source, Process target, IFn lcb, IFn rcb) {
            this.source = source;
            this.target = target;
            this.lcb = lcb;
            this.rcb = rcb;
        }

        @Override
        public Object invoke() {
            return unsub(this);
        }

        @Override
        public Object deref() {
            return accept(this);
        }
    }

    class Context {
        long time;               // time increment
        boolean busy;            // currently propagating
        Process process;         // process currently running
        Subscription sub;        // subscription currently running
        Publisher emitter;       // publisher currently emitting
        Publisher reacted;       // pairing heap of publishers scheduled for this turn.
        Publisher delayed;       // pairing heap of publishers scheduled for next turn.
    }

    ThreadLocal<Context> context = ThreadLocal.withInitial(Context::new);

    AtomicInteger children = new AtomicInteger();

    static boolean lt(int[] x, int[] y) {
        int xl = x.length;
        int yl = y.length;
        int ml = Math.min(xl, yl);
        for(int i = 0; i < ml; i++) {
            int xi = x[i];
            int yi = y[i];
            if (xi != yi) return xi < yi;
        }
        return xl > yl;
    }

    static Publisher link(Publisher x, Publisher y) {
        if (lt(x.ranks, y.ranks)) {
            y.sibling = x.child;
            x.child = y;
            return x;
        } else {
            x.sibling = y.child;
            y.child = x;
            return y;
        }
    }

    static Publisher dequeue(Publisher pub) {
        Publisher heap = null;
        Publisher prev = null;
        Publisher head = pub.child;
        pub.child = null;
        while (head != null) {
            Publisher next = head.sibling;
            head.sibling = null;
            if (prev == null) prev = head;
            else {
                head = link(prev, head);
                heap = heap == null ? head : link(heap, head);
                prev = null;
            }
            head = next;
        }
        return prev == null ? heap : heap == null ? prev : link(heap, prev);
    }

    static Publisher enqueue(Publisher r, Publisher p) {
        return r == null ? p : link(p, r);
    }

    static boolean enter(Publisher pub) {
        boolean held = pub.lock.isHeldByCurrentThread();
        pub.lock.lock();
        return held;
    }

    static void cancel(Process ps) {
        ps.parent.current = null;
        ((IFn) ps.process).invoke();
    }

    static void propagate(Context ctx, Process ps, Subscription sub) {
        Publisher pub = ps.parent;
        ctx.sub = null;
        while (sub != null) {
            IFn cb = sub.flag ? sub.lcb : sub.rcb;
            Subscription n = sub.prop;
            sub.prop = null;
            ctx.process = sub.source;
            if (pub.accept == null) cb.invoke(sub.state);
            else cb.invoke();
            sub = n;
        }
    }

    static void tick(Publisher pub, Context ctx) {
        pub.lock.lock();
        Process ps = pub.current;
        ctx.reacted = dequeue(pub);
        ctx.emitter = pub;
        ctx.process = ps;
        pub.tick.invoke();
        Subscription sub = pub.prop;
        pub.prop = null;
        pub.lock.unlock();
        propagate(ctx, ps, sub);
    }

    static void exit(Context ctx, boolean held, boolean b, Process p, Subscription s) {
        Process ps = ctx.process;
        Publisher pub = ps.parent;
        Subscription sub;
        if (held) sub = null; else {
            sub = pub.prop;
            pub.prop = null;
        }
        pub.lock.unlock();
        propagate(ctx, ps, sub);
        if (!b) {
            ctx.sub = null;
            for(;;) {
                pub = ctx.reacted;
                if (pub == null) {
                    ctx.time++;
                    pub = ctx.delayed;
                    if (pub == null) break; else {
                        ctx.delayed = null;
                        tick(pub, ctx);
                    }
                } else tick(pub, ctx);
            }
            ctx.emitter = null;
        }
        ctx.busy = b;
        ctx.process = p;
        ctx.sub = s;
    }

    static void attach(Subscription n, Subscription s) {
        if (n == null) {
            s.prev = s;
            s.next = s;
        } else {
            Subscription p = n.prev;
            s.next = n;
            s.prev = p;
            p.next = s;
            n.prev = s;
        }
    }

    static void dispatch(Subscription s) {
        Process ps = s.target;
        Subscription p = s.prev;
        Subscription n = s.next;
        s.prev = s.next = null;
        if (p == s) ps.waiting = null; else {
            n.prev = p;
            p.next = n;
            ps.waiting = n;
        }
        Publisher pub = ps.parent;
        s.prop = pub.prop;
        pub.prop = s;
    }

    static void detach(Subscription s) {
        Process ps = s.target;
        Subscription p = s.prev;
        Subscription n = s.next;
        s.prev = s.next = null;
        if (p == s) ps.pending = null; else {
            n.prev = p;
            p.next = n;
            ps.pending = n;
        }
    }

    static void foreach(Context ctx, Subscription subs, IFn f) {
        if (subs != null) {
            Subscription s = ctx.sub;
            Subscription sub = subs.next;
            for(;;) {
                Subscription n = sub.next;
                ctx.sub = sub;
                f.invoke();
                if (sub == subs) break;
                else sub = n;
            }
            ctx.sub = s;
        }
    }

    static Object accept(Subscription sub) {
        Context ctx = context.get();
        Process ps = sub.target;
        Publisher pub = ps.parent;
        boolean held = enter(pub);
        boolean b = ctx.busy;
        Process p = ctx.process;
        Subscription s = ctx.sub;
        try {
            ctx.busy = true;
            ctx.process = ps;
            ctx.sub = sub;
            sub.flag = false;
            if (sub.next == null) {
                sub.prop = pub.prop;
                pub.prop = sub;
                return clojure.lang.Util.sneakyThrow(new Cancelled("Flow publisher cancelled."));
            } else {
                detach(sub);
                attach(ps.waiting, ps.waiting = sub);
                return pub.accept.invoke();
            }
        } finally {
            exit(ctx, held, b, p, s);
        }
    }

    static Object unsub(Subscription sub) {
        Context ctx = context.get();
        Process ps = sub.target;
        Publisher pub = ps.parent;
        boolean held = enter(pub);
        boolean b = ctx.busy;
        Process p = ctx.process;
        Subscription s = ctx.sub;
        try {
            ctx.busy = true;
            ctx.process = ps;
            ctx.sub = sub;
            if (sub.next != null) if (pub.effect != null) if (pub.current == ps) {
                if (pub.accept == null) if (sub.next == sub) cancel(ps); else {
                    sub.state = new Cancelled("Task publisher cancelled.");
                    dispatch(sub);
                } else if (sub.flag) if (sub.next == sub && ps.waiting == null) cancel(ps); else {
                    detach(sub);
                    pub.reject.invoke();
                } else if (sub.next == sub && ps.pending == null) cancel(ps); else {
                    sub.flag = true;
                    dispatch(sub);
                }
            }
            return null;
        } finally {
            exit(ctx, held, b, p, s);
        }
    }

    static IFn bind(Process ps, IFn f) {
        return new AFn() {
            @Override
            public Object invoke() {
                Context ctx = context.get();
                boolean held = enter(ps.parent);
                boolean b = ctx.busy;
                Process p = ctx.process;
                Subscription s = ctx.sub;
                try {
                    ctx.busy = true;
                    ctx.process = ps;
                    ctx.sub = null;
                    return f.invoke();
                } finally {
                    exit(ctx, held, b, p, s);
                }
            }

            @Override
            public Object invoke(Object x) {
                Context ctx = context.get();
                boolean held = enter(ps.parent);
                boolean b = ctx.busy;
                Process p = ctx.process;
                Subscription s = ctx.sub;
                try {
                    ctx.busy = true;
                    ctx.process = ps;
                    ctx.sub = null;
                    return f.invoke(x);
                } finally {
                    exit(ctx, held, b, p, s);
                }
            }
        };
    }

    static Subscription sub(Publisher pub, IFn lcb, IFn rcb) {
        Context ctx = context.get();
        boolean held = enter(pub);
        boolean b = ctx.busy;
        Process p = ctx.process;
        Subscription s = ctx.sub;
        try {
            ctx.busy = true;
            Process ps = pub.current;
            if (ps == null) {
                ps = new Process(pub);
                ps.state = pub.initp;
                pub.current = ps;
                ctx.process = ps;
                ctx.sub = null;
                pub.perform.invoke();
                ps.process = pub.effect.invoke(bind(ps, pub.lcb), bind(ps, pub.rcb));
            } else ctx.process = ps;
            Subscription sub = new Subscription(p, ps, lcb, rcb);
            sub.state = pub.inits;
            attach(ps.waiting, ps.waiting = sub);
            ctx.sub = sub;
            pub.subscribe.invoke();
            return sub;
        } finally {
            exit(ctx, held, b, p, s);
        }
    }

    static int[] ranks() {
        Process ps = context.get().process;
        if (ps == null) return new int[] {children.getAndIncrement()}; else {
            Publisher p = ps.parent;
            int[] r = p.ranks;
            int s = r.length;
            int[] ranks = new int[s + 1];
            System.arraycopy(r, 0, ranks, 0, s);
            ranks[s] = p.children.getAndIncrement();
            return ranks;
        }
    }

    // public API

    static long time() {
        return context.get().time;
    }

    static Object transfer() {
        return ((IDeref) context.get().process.process).deref();
    }

    static Object getp() {
        return context.get().process.state;
    }

    static void setp(Object x) {
        context.get().process.state = x;
    }

    static Object gets() {
        return context.get().sub.state;
    }

    static void sets(Object x) {
        context.get().sub.state = x;
    }

    static void success(Object x) {
        Subscription sub = context.get().sub;
        sub.flag = true;
        sub.state = x;
        dispatch(sub);
    }

    static void failure(Object x) {
        Subscription sub = context.get().sub;
        sub.state = x;
        dispatch(sub);
    }

    static void step() {
        Subscription sub = context.get().sub;
        sub.flag = true;
        dispatch(sub);
        Process ps = sub.target;
        attach(ps.pending, ps.pending = sub);
    }

    static void done() {
        Subscription sub = context.get().sub;
        dispatch(sub);
    }

    static void waiting(IFn f) {
        Context ctx = context.get();
        foreach(ctx, ctx.process.waiting, f);
    }

    static void pending(IFn f) {
        Context ctx = context.get();
        foreach(ctx, ctx.process.pending, f);
    }

    static void schedule() {
        Context ctx = context.get();
        Process ps = ctx.process;
        Publisher pub = ps.parent;
        if (pub.current == ps && ps.process != null) {
            Publisher emitter = ctx.emitter;
            if (emitter == null || lt(emitter.ranks, pub.ranks))
                ctx.reacted = enqueue(ctx.reacted, pub);
            else ctx.delayed = enqueue(ctx.delayed, pub);
        } else pub.tick.invoke();
    }

    static void resolve() {
        Process ps = context.get().process;
        Publisher pub = ps.parent;
        if (ps == pub.current) pub.effect = null;
    }

    static Publisher task(Object initp, Object inits,
                          IFn perform, IFn subscribe, IFn success, IFn failure,
                          IFn tick, IFn task) {
        return new Publisher(ranks(), initp, inits, perform, subscribe, success, failure, tick, null, null, task);
    }

    static Publisher flow(Object initp, Object inits,
                          IFn perform, IFn subscribe, IFn step, IFn done,
                          IFn tick, IFn accept, IFn reject, IFn flow) {
        return new Publisher(ranks(), initp, inits, perform, subscribe, step, done, tick, accept, reject, flow);
    }
}
