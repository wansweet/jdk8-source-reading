package test.java.nio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 读一个有几千万行的字符串文本，每一行都是字母和数字组成，每一行不超过256个字节
 * 读出来之后将其排序输出，按照字符串的自然排序升序
 *
 * 多次测试下来，新生代代大小对性能存在明显影响。
 * 对于1000万文本（800多M），新生代1G比较好:-Xms3100M -Xmx3100M -Xmn1G -XX:+PrintGCDetails
 *
 * @author charpty
 * @since 2018/1/1
 */
public class ReadFileAndSort3 {

	private static final byte[] LINE = "\n".getBytes();
	private static final byte LF = 10;
	// 前16位代表所属固定块数组
	private static final long ARRAY_INDEX_SHIFT = 0x7FFF000000000000L;
	// 第17-50位代表开始地址
	private static final long START_SHIFT = 0x0000FFFFFF000000L;
	// 后51-64位代表结束地址
	private static final long END_SHIFT = 0x0000000000FFFFFFL;

	public static void main(String[] args) throws IOException {
		long start = System.nanoTime();
		// 因为题目规定只有字符和数字，所以一共26+26+10=62个桶即可
		// 最长的一行字符串长度,因为256并不大，所以直接使用每一种长度作为一个桶即可
		int strLen = 256;
		// 使用一层桶时效率最高
		LinkedList[] buckets = new LinkedList[62 * strLen];
		RandomAccessFile raf = new RandomAccessFile(new File("/tmp/large.file"), "r");

		ExecutorService executor = Executors.newCachedThreadPool();

		FileChannel channel = raf.getChannel();
		// 一次读4M,2^22
		// 24 + 24 + 16
		ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024 * 1);
		int count = 0;
		long readNanos = 0;
		long divideNanos = 0;
		long tmpNanos = 0;
		// 40960个字符中至多有4096个字符串？在我们的场景中是这样的
		// 使用一个long的中间24位存储字符串起始位置，使用低24位存储结束位置
		byte[][] mem = new byte[1 << 16][];
		int mi = 0;
		// 1000万个字符串
		long[] indexArr = new long[20_000_000];

		while (true) {
			long beforeRead = System.nanoTime();
			if ((channel.read(buffer)) < 0) {
				break;
			}
			long afterRead = System.nanoTime();
			readNanos = readNanos + (afterRead - beforeRead);
			byte[] array = buffer.array();
			int position = buffer.position();
			byte[] tmpArr = new byte[position];
			// 一次性拷贝好内存，避免大量重复分配与拷贝
			System.arraycopy(array, 0, tmpArr, 0, position);
			// 自解析，一般来说都是满的
			// 由于都是字母和数字所以对应就是一个byte就是一个char
			// TODO 是否可以引入多线程并发分割
			int p = 0;
			for (int i = 0; i < position; i++) {
				if (array[i] == LF) {
					indexArr[count++] = i | (((long) p) << 24) | (((long) mi) << 48);
					p = i + 1;
				}
			}
			tmpNanos = tmpNanos + (System.nanoTime() - afterRead);
			mem[mi++] = tmpArr;
			buffer.rewind();
			if (p < array.length - 1) {
				buffer.put(array, p, array.length - p);
			}
		}
		long beforeDivide = System.nanoTime();
		// 按照字母分桶
		for (int i = 0; i < count; i++) {
			long idx = indexArr[i];
			int m = (int) ((idx & ARRAY_INDEX_SHIFT) >>> 48);
			int s = (int) ((idx & START_SHIFT) >>> 24);
			int e = (int) (idx & END_SHIFT);
			int len = e - s;
			// 先按长度分桶
			int index = len * 62;
			// 根据level将元素多级划分到不同桶
			int offset = 0;
			byte f = mem[m][s];
			if (f <= '9') {
				// 只有字符和数字
				offset = f - '0';
			} else if (f <= 'Z') {
				offset = f - 'A' + 10;
			} else {
				offset = f - 'a' + 36;
			}
			index = index + offset;
			LinkedList<Long> list = buckets[index];
			if (list == null) {
				list = new LinkedList();
				buckets[index] = list;
			}
			list.add(idx);
		}
		divideNanos = divideNanos + (System.nanoTime() - beforeDivide);
		long afterDivide = System.nanoTime();

		//		System.out.println(count);
		//		System.out.println("tmpNanos" + TimeUnit.NANOSECONDS.toMillis(tmpNanos));
		//		System.out.println("divideNanos" + TimeUnit.NANOSECONDS.toMillis(divideNanos));
		//		System.out.println(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
		//		System.exit(0);
		// 开始排序
		RandomAccessFile result = new RandomAccessFile(new File("/tmp/result.file"), "rw");
		FixCharsComparator comparator = new FixCharsComparator(mem);
		FileChannel outputChannel = result.getChannel();

		final Boolean[] flags = new Boolean[buckets.length];
		// 要尽量保证排在前面的任务先执行
		for (int i = 0; i < buckets.length; i++) {
			LinkedList<Long> list = buckets[i];
			if (list == null || list.isEmpty()) {
				continue;
			}
			flags[i] = false;
			final int x = i;
			executor.submit(() -> {
				Collections.sort(list, comparator);
				flags[x] = true;
			});
		}

		// 排序的动作可以并行
		ByteBuffer tmp = ByteBuffer.allocate(4096000);
		long writeNanos = 0;
		for (int i = 0; i < flags.length; i++) {
			Boolean flag = flags[i];
			if (flag != null) {
				while (!flag.booleanValue()) {

				}
				LinkedList<Long> list = buckets[i];
				// 最长256
				int capacity = list.size() * 256;
				if (capacity > tmp.capacity()) {
					tmp = ByteBuffer.allocate(capacity);
				}
				for (long l : list) {
					int m = (int) ((l & ARRAY_INDEX_SHIFT) >>> 48);
					int s = (int) ((l & START_SHIFT) >>> 24);
					int e = (int) (l & END_SHIFT);
					tmp.put(mem[m], s, e - s).put(LINE);
				}
				tmp.flip();
				long t1 = System.nanoTime();
				outputChannel.write(tmp);
				writeNanos = writeNanos + (System.nanoTime() - t1);
				tmp.rewind();
				tmp.clear();
			}
		}
		executor.shutdown();
		long end = System.nanoTime();
		System.out.println("tmp" + TimeUnit.NANOSECONDS.toMillis(tmpNanos));
		System.out.println("共有" + count + "行");
		System.out.println("读操作IO耗时：" + TimeUnit.NANOSECONDS.toMillis(readNanos));
		System.out.println("单独分桶操作耗时：" + TimeUnit.NANOSECONDS.toMillis(divideNanos));
		System.out.println("读取并分桶共耗时：" + TimeUnit.NANOSECONDS.toMillis((afterDivide - start)));
		System.out.println("排序耗时：" + TimeUnit.NANOSECONDS.toMillis((end - afterDivide - writeNanos)));
		System.out.println("写操作IO耗时：" + TimeUnit.NANOSECONDS.toMillis(writeNanos));
		// 10131015行约21秒
		System.out.println("共耗时" + TimeUnit.NANOSECONDS.toMillis((end - start)));
	}

	static class FixCharsComparator implements Comparator<Long> {

		private final byte[][] mem;

		public FixCharsComparator(byte[][] mem) {
			this.mem = mem;
		}

		@Override
		public int compare(Long l1, Long l2) {
			int m1 = (int) ((l1 & ARRAY_INDEX_SHIFT) >>> 48);
			int s1 = (int) ((l1 & START_SHIFT) >>> 24);
			int e1 = (int) (l1 & END_SHIFT);

			int m2 = (int) ((l2 & ARRAY_INDEX_SHIFT) >>> 48);
			int s2 = (int) ((l2 & START_SHIFT) >>> 24);

			byte[] b1 = mem[m1];
			byte[] b2 = mem[m2];

			// 长度总是相等的
			while (s1 < e1) {
				byte bb1 = b1[++s1];
				byte bb2 = b2[++s2];
				if (bb1 < bb2) {
					return -1;
				} else if (bb1 > bb2) {
					return 1;
				}
			}
			return 0;
		}
	}
}
