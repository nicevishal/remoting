/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.StringWriter;
import java.util.EnumSet;
import java.util.Set;

/**
 * Wraps {@link InputStream} so that it can be sent over the remoting channel.
 *
 * <p>
 * Note that this class by itself does not perform buffering.
 *
 * @author Kohsuke Kawaguchi
 */
public class RemoteInputStream extends InputStream implements Serializable {
    private transient InputStream core;
    private boolean autoUnexport;
    private transient Greedy greedyAt;
    private boolean greedy;

    /**
     * Short for {@code RemoteInputStream(core,true)}.
     *
     * @deprecated as of 2.35
     *      Use {@link #of(InputStream,Flag)} and consider using {@link Flag#GREEDY}
     *      to improve the performance.
     */
    public RemoteInputStream(InputStream core) {
        this(core,true);
    }

    /**
     * @param autoUnexport
     *      If true, the {@link InputStream} will be automatically unexported when
     *      the callable that took it with returns. If false, it'll not unexported
     *      until the close method is called.
     *
     * @deprecated as of 2.35
     *      Use {@link #of(InputStream,Flag, Flag)} with {@link Flag#MANUAL_UNEXPORT}.
     *      Also consider using {@link Flag#GREEDY} to improve the performance.
     */
    public RemoteInputStream(InputStream core, boolean autoUnexport) {
        this.core = core;
        this.autoUnexport = autoUnexport;
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        Channel ch = Channel.current();
        if (ch.remoteCapability.supportsGreedyRemoteInputStream()) {
            oos.writeBoolean(greedy);

            if (greedy) {
                Pipe pipe = Pipe.createLocalToRemote();
                final InputStream i = core;
                final OutputStream o = pipe.getOut();

                new Thread("RemoteInputStream greedy pump thread: " + greedyAt.print()) {
                    {
                        setDaemon(true);
                    }

                    public void run() {
                        try {
                            try {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = i.read(buf)) >= 0)
                                    o.write(buf, 0, len);
                            } finally {
                                // it doesn't make sense not to close InputStream that's already EOF-ed,
                                // so there's no 'closeIn' flag.
                                i.close();
                                o.close();
                            }
                        } catch (IOException e) {
                            // TODO: we want to pass this exception to the reading end of the pipe
                        }
                    }
                }.start();
                oos.writeObject(pipe);
                return;
            }
        }

        int id = ch.export(core, autoUnexport);
        oos.writeInt(id);
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        final Channel channel = Channel.current();
        assert channel !=null;

        if (channel.remoteCapability.supportsGreedyRemoteInputStream()) {
            boolean greedy = ois.readBoolean();
            if (greedy) {
                Pipe p = (Pipe)ois.readObject();
                this.core = p.getIn();
                return;
            }
        }

        this.core = new ProxyInputStream(channel, ois.readInt());
    }

    private static final long serialVersionUID = 1L;

    public enum Flag {
        /**
         * Set this flag to greedily drain the input stream wrapped in {@link RemoteInputStream}
         * and send data to the other side.
         *
         * {@link RemoteInputStream} is normally unbuffered, in the sense that it will
         * never attempt to read ahead and only read bytes that are explicitly requested via
         * {@link InputStream#read(byte[], int, int)} methods (and other overloads.)
         *
         * <p>
         * This is sometimes important, for example if you are going to pass InputStream
         * to the other side, read specific amount of bytes from the other side,
         * then come back to this side and keep reading.
         *
         * <p>
         * But inability to read ahead means every {@link #read(byte[], int, int)} call incurs
         * a remote roundtrip. A local buffering via {@link BufferedInputStream} would help,
         * but you'd still block on a roundtrip whenever a buffer goes empty.
         *
         * <p>
         * When this flag is set, it changes the underlying data transfer model of
         * {@link RemoteInputStream} from pull to push. The side that created {@link RemoteInputStream}
         * will launch a thread and start proactively sending data to the other side.
         * The side that received {@link RemoteInputStream} will buffer this content,
         * and so now {@link RemoteInputStream#read(byte[], int, int)} will only block
         * when there's no data. In this way, it hides the network latency completely
         * when you send a large amount of data.
         *
         * <p>
         * When communicating with earlier version of the remoting library on the other side,
         * the channel falls back and behaves as if this flag was not specified.
         */
        GREEDY,

        /**
         * A dummy flag to make it explicit that the particular use case prevents you from
         * setting {@link #GREEDY} flag.
         *
         * The lack of {@link #GREEDY} flag incurs a considerable performance penalty, so
         * when a developer chooses to do so, it's good to record that explicitly, hence this flag.
         */
        NOT_GREEDY,

        /**
         * If a Callable captures a {@link RemoteInputStream} on its way to the other wide,
         * {@link RemoteInputStream} gets unexported automatically when the callable returns.
         *
         * If this flag is set, this will not happen, and the input stream must be explicitly closed
         * to get unexported.
         */
        MANUAL_UNEXPORT
    }

    /**
     * Creates a {@link RemoteInputStream}.
     *
     * @param f
     *      At least you should specify {@link Flag#GREEDY} or {@link Flag#NOT_GREEDY}.
     */
    public static RemoteInputStream of(InputStream base, Flag f) {
        return of(base, EnumSet.of(f));
    }

    public static RemoteInputStream of(InputStream base, Flag f1, Flag f2) {
        return of(base, EnumSet.of(f1,f2));
    }

    public static RemoteInputStream of(InputStream base, Set<Flag> flags) {
        RemoteInputStream r = new RemoteInputStream(base);
        r.greedy = flags.contains(Flag.GREEDY);
        if (r.greedy)
            r.greedyAt = new Greedy();
        r.autoUnexport = !flags.contains(Flag.MANUAL_UNEXPORT);
        return r;
    }

    /**
     * Used to capture where greedy {@link RemoteInputStream} was created.
     */
    private static final class Greedy extends Exception {
        public String print() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            printStackTrace(pw);
            pw.close();
            return sw.toString();
        }
    }

//
//
// delegation to core
//
//

    public int read() throws IOException {
        return core.read();
    }

    public int read(byte[] b) throws IOException {
        return core.read(b);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        return core.read(b, off, len);
    }

    public long skip(long n) throws IOException {
        return core.skip(n);
    }

    public int available() throws IOException {
        return core.available();
    }

    public void close() throws IOException {
        core.close();
    }

    public void mark(int readlimit) {
        core.mark(readlimit);
    }

    public void reset() throws IOException {
        core.reset();
    }

    public boolean markSupported() {
        return core.markSupported();
    }
}
