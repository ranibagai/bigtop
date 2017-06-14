/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs;

import java.io.IOException;
import java.io.OutputStream;
import java.security.PrivilegedExceptionAction;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.client.HdfsDataInputStream;
import org.apache.hadoop.hdfs.protocol.RecoveryInProgressException;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Test;

/** Test reading from hdfs while a file is being written. */
public class TestReadWhileWriting {
  {
    GenericTestUtils.setLogLevel(FSNamesystem.LOG, Level.ALL);
    GenericTestUtils.setLogLevel(DFSClient.LOG, Level.ALL);
  }

  private static final String DIR = "/"
      + TestReadWhileWriting.class.getSimpleName() + "/";
  private static final int BLOCK_SIZE = 8192;
  // soft limit is short and hard limit is long, to test that
  // another thread can lease file after soft limit expired
  // ADL: 10 min + 1 min (buffer)
  private static final long SOFT_LEASE_LIMIT = 660;
  private static final long HARD_LEASE_LIMIT = 1000*600; 
  
  /** Test reading while writing. */
  @Test
  public void pipeline_02_03() throws Exception {
   System.out.println("ADL: Start of pipeline_02_03");
    final Configuration conf = new Configuration();
    conf.setLong(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);

    try {
      final FileSystem fs = FileSystem.get(conf);
      final Path p = new Path(DIR, "file1");
      final int half = BLOCK_SIZE/2;

      //a. On Machine M1, Create file. Write half block of data.
      //   Invoke DFSOutputStream.hflush() on the dfs file handle.
      //   Do not close file yet.
      {
 	System.out.println("ADL: Writing half a block");
        final FSDataOutputStream out = fs.create(p, true,
            fs.getConf().getInt(CommonConfigurationKeys.IO_FILE_BUFFER_SIZE_KEY, 4096),
            (short)3, BLOCK_SIZE);
        write(out, 0, half);

           out.hflush();
           out.flush();
      }

      //b. On another machine M2, open file and verify that the half-block
      //   of data can be read successfully.
      System.out.println("ADL: CheckFile");
      checkFile(p, half, conf);
      AppendTestUtil.LOG.info("leasechecker.interruptAndJoin()");
//      ((FileSystem)fs).dfs.getLeaseRenewer().interruptAndJoin();

      //c. On M1, append another half block of data.  Close file on M1.
      {
        //sleep to let the lease is expired.
        int sleepMinutes = (int)(SOFT_LEASE_LIMIT/60);
	for (int i = 0; i < sleepMinutes; i++)
	{
       		System.out.println("Sleep minute: " + i);         
		Thread.sleep(60 * 1000);
	}
  
        final UserGroupInformation current = UserGroupInformation.getCurrentUser();
        final UserGroupInformation ugi = UserGroupInformation.createUserForTesting(
            current.getShortUserName() + "x", new String[]{"supergroup"});
        final FileSystem dfs = ugi.doAs(
            new PrivilegedExceptionAction<FileSystem>() {
          @Override
          public FileSystem run() throws Exception {
            return (FileSystem)FileSystem.newInstance(conf);
          }
        });
        final FSDataOutputStream out = append(dfs, p);
        write(out, 0, half);
        out.close();
      }

      //d. On M2, open file and read 1 block of data from it. Close file.
      checkFile(p, 2*half, conf);
    } finally {
    }
  }

  /** Try openning a file for append. */
  private static FSDataOutputStream append(FileSystem fs, Path p) throws Exception {
    for(int i = 0; i < 10; i++) {
      try {
        return fs.append(p);
      } catch(RemoteException re) {
        if (re.getClassName().equals(RecoveryInProgressException.class.getName())) {
          AppendTestUtil.LOG.info("Will sleep and retry, i=" + i +", p="+p, re);
          Thread.sleep(1000);
        }
        else
          throw re;
      }
    }
    throw new IOException("Cannot append to " + p);
  }

  static private int userCount = 0;
  //check the file
  static void checkFile(Path p, int expectedsize, final Configuration conf
      ) throws IOException, InterruptedException {
    //open the file with another user account
    final String username = UserGroupInformation.getCurrentUser().getShortUserName()
        + "_" + ++userCount;

    UserGroupInformation ugi = UserGroupInformation.createUserForTesting(username, 
                                 new String[] {"supergroup"});
    
    final FileSystem fs = FileSystem.get(conf);
    
     final FSDataInputStream in = fs.open(p);

    //Able to read?
    for(int i = 0; i < expectedsize; i++) {
      Assert.assertEquals((byte)i, (byte)in.read());  
    }

    in.close();
  }

  /** Write something to a file */
  private static void write(OutputStream out, int offset, int length
      ) throws IOException {
    final byte[] bytes = new byte[length];
    for(int i = 0; i < length; i++) {
      bytes[i] = (byte)(offset + i);
    }
    out.write(bytes);
  }
}
