// Copyright (c) 2006  Dustin Sallings <dustin@spy.net>
// arch-tag: 30573332-B549-4E6F-AD59-04C6D0928419

package net.spy.memcached;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import net.spy.SpyObject;
import net.spy.memcached.ops.Operation;

/**
 * Connection to a cluster of memcached 
 */
public class MemcachedConnection extends SpyObject {
	// The maximum number of empty results from selects that may occur without
	// some actual values coming back.
	private static final int MAX_EMPTY = 1000;

	private Selector selector=null;
	private SelectionKey[] connections=null;
	private int emptySelects=0;

	public MemcachedConnection(InetSocketAddress[] a) throws IOException {
		selector=Selector.open();
		connections=new SelectionKey[a.length];
		int cons=0;
		for(SocketAddress sa : a) {
			SocketChannel ch=SocketChannel.open(sa);
			ch.configureBlocking(false);
			QueueAttachment qa=new QueueAttachment(sa, ch);
			connections[cons]=ch.register(selector, 0, qa);
			qa.sk=connections[cons];
			qa.which=cons;
			cons++;
		}
	}

	@SuppressWarnings("unchecked")
	public void handleIO() throws IOException {
		int selected=selector.select();
		Set<SelectionKey> selectedKeys=selector.selectedKeys();
		getLogger().debug("Selected %d, selected %d keys",
				selected, selectedKeys.size());
		if(selectedKeys.size() > 0) {
			emptySelects=0;
			for(SelectionKey sk : selectedKeys) {
				getLogger().debug(
						"Got selection key:  %s (r=%s, w=%s, c=%s, op=%s)",
						sk, sk.isReadable(), sk.isWritable(),
						sk.isConnectable(), sk.attachment());
				handleIO(sk);
			} // for each selector
		} else {
			getLogger().info("No selectors ready, interrupted: "
				+ Thread.interrupted());
			if(++emptySelects > MAX_EMPTY) {
				for(SelectionKey sk : selector.keys()) {
					getLogger().info("%s has %s, interested in %s",
							sk, sk.readyOps(), sk.interestOps());
				}
				assert false : "Too many empty selects";
			}
		}
		selectedKeys.clear();
	}

	// Handle IO for a specific selector.
	private void handleIO(SelectionKey sk) throws IOException {
		QueueAttachment qa=(QueueAttachment)sk.attachment();
		if(sk.isConnectable()) {
			getLogger().warn("Connection state changed for " + sk);
			try {
				if(qa.channel.finishConnect()) {
					synchronized(qa) {
						if(qa.ops.size() > 0) {
							sk.interestOps(SelectionKey.OP_WRITE);
						} else {
							sk.interestOps(0);
						}
					}
				}
			} catch(IOException e) {
				reconnect(qa);
			}
		} else {
			Operation currentOp=qa.ops.peek();
			if(currentOp != null) {
				handleOperation(currentOp, sk, qa);
			} else {
				if(sk.isReadable()) {
					ByteBuffer b=ByteBuffer.allocate(1);
					int read=qa.channel.read(b);
					assert read == -1
						: "expected to read -1 bytes, read " + read;
					reconnect(qa);
				} else {
					assert false : "No current operations, but selectors ready";
				}
			}
		}
	}

	// Handle IO for an operation.
	private void handleOperation(Operation currentOp, SelectionKey sk,
			QueueAttachment qa) throws IOException {
		getLogger().debug("Current operation: %s", currentOp);
		// First switch is for IO.
		switch(currentOp.getState()) {
			case READING:
				assert !sk.isWritable() : "While reading, came up writable";
				if(sk.isReadable()) {
					int read=qa.channel.read(qa.buf);
					if(read < 0) {
						reconnect(qa);
					} else {
						qa.buf.flip();
						currentOp.readFromBuffer(qa.buf);
						qa.buf.clear();
					}
				} else {
					assert false : "While reading, came up not readable.";
				}
				break;
			case WRITING:
				if(sk.isReadable()) {
					ByteBuffer b=ByteBuffer.allocate(1);
					int read=qa.channel.read(b);
					assert read == -1
						: "expected to read -1 bytes, read " + read;
					reconnect(qa);
				} else if(sk.isWritable()) {
					ByteBuffer b=currentOp.getBuffer();
					int written=qa.channel.write(b);
					getLogger().debug("Wrote %d bytes for %s",
							written, currentOp);
					if(b.remaining() == 0) {
						currentOp.writeComplete();
					}
				} else {
					assert false : "Not readable or writable.";
				}
				break;
			case COMPLETE:
				assert false : "Current op is in complete state";
				break;
		}
		// Second switch is for post-IO examination and state transition
		switch(currentOp.getState()) {
			case READING:
				if(sk.isValid()) {
					sk.interestOps(SelectionKey.OP_READ);
				}
				break;
			case WRITING:
				getLogger().debug("Operation is still writing.");
				if(sk.isValid()) {
					sk.interestOps(SelectionKey.OP_WRITE);
				}
				break;
			case COMPLETE:
				qa.ops.remove();
				// If there are more operations in the queue, tell
				// it we want to write
				synchronized(qa) {
					if(qa.ops.size() > 0 && sk.isValid()) {
						sk.interestOps(SelectionKey.OP_WRITE);
					}
				}
				break;
		}
	}

	private void reconnect(QueueAttachment qa) throws IOException {
		getLogger().warn("Closing, and reopening connection.");
		synchronized(qa) {
			qa.sk.cancel();
			SocketChannel ch=SocketChannel.open();
			ch.configureBlocking(false);
			ch.connect(qa.socketAddress);
			qa.channel.socket().close();
			qa.channel=ch;
			connections[qa.which]=ch.register(selector, 0, qa);
			qa.sk=connections[qa.which];
			qa.sk.interestOps(SelectionKey.OP_CONNECT);

			// Need to do a little queue management.
			setupResend(qa);
		}
	}

	private void setupResend(QueueAttachment qa) {
		if(qa.ops.size() > 0) {
			Operation op=qa.ops.peek();
			if(op.getState() == Operation.State.WRITING) {
				getLogger().warn("Resetting write state of op: %s", op);
				op.getBuffer().reset();
			} else {
				getLogger().warn(
						"Discarding partially completed operation: %s", op);
				qa.ops.remove();
			}
		}
	}

	/**
	 * Get the number of connections currently handled.
	 */
	public int getNumConnections() {
		return connections.length;
	}

	/**
	 * Get the remote address of the socket with the given ID.
	 * 
	 * @param which which id
	 * @return the rmeote address
	 */
	public SocketAddress getAddressOf(int which) {
		QueueAttachment qa=(QueueAttachment)connections[which].attachment();
		return qa.socketAddress;
	}

	/**
	 * Add an operation to the given connection.
	 * 
	 * @param which the connection offset
	 * @param o the operation
	 */
	@SuppressWarnings("unchecked")
	public void addOperation(int which, Operation o) {
		QueueAttachment qa=(QueueAttachment)connections[which].attachment();
		o.initialize();
		synchronized(qa) {
			qa.ops.add(o);
			// If this is the only operation in the queue, tell the selector
			// we want to write
			if(qa.ops.size() == 1) {
				qa.sk.interestOps(SelectionKey.OP_WRITE);
				selector.wakeup();
			}
		}
		getLogger().debug("Added %s to %d", o, which);
	}

	/**
	 * Shut down all of the connections.
	 */
	public void shutdown() throws IOException {
		for(SelectionKey sk : connections) {
			QueueAttachment qa=(QueueAttachment)sk.attachment();
			qa.channel.close();
			qa.sk=null;
			getLogger().debug("Shut down channel %s", qa.channel);
		}
		selector.close();
		getLogger().debug("Shut down selector %s", selector);
	}

	private static class QueueAttachment {
		public int which=0;
		public SocketAddress socketAddress=null;
		public SocketChannel channel=null;
		public ByteBuffer buf=null;
		public LinkedBlockingQueue<Operation> ops=null;
		public SelectionKey sk=null;
		public QueueAttachment(SocketAddress sa, SocketChannel c) {
			super();
			socketAddress=sa;
			channel=c;
			buf=ByteBuffer.allocate(4096);
			ops=new LinkedBlockingQueue<Operation>();
		}

		public String toString() {
			return "{QA #ops=" + ops.size() + ", topop=" + ops.peek() + "}";
		}
	}
}
