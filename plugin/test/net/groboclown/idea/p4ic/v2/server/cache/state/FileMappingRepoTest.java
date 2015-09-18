/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.groboclown.idea.p4ic.v2.server.cache.state;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FilePathImpl;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class FileMappingRepoTest {
    @Test
    public void testGetAllFilesInsensitive() throws Exception {
        final FileMappingRepo repo = new FileMappingRepo(true);

        final File f1 = new File("f1");
        final FilePath fp1 = createFilePath(f1);
        repo.getByDepotLocation("//depot/file1", fp1);

        final File f2 = new File("f2");
        final FilePath fp2 = createFilePath(f2);
        repo.getByDepotLocation("//depot/file2", fp2);

        final File f3 = new File("f3");
        final FilePath fp3 = createFilePath(f3);
        // case insensitive
        repo.getByDepotLocation("//depot/FILE1", fp3);

        final Iterable<P4ClientFileMapping> iterable = repo.getAllFiles();
        // ensure the iterable can be used multiple times
        for (int i = 0; i < 3; i++) {
            List<P4ClientFileMapping> expected = new ArrayList<P4ClientFileMapping>(Arrays.asList(
                new P4ClientFileMapping("//depot/file2", fp2),
                new P4ClientFileMapping("//depot/file1", fp3)
            ));

            final Iterator<P4ClientFileMapping> iter = iterable.iterator();
            assertThat(iter.hasNext(), is(true));

            P4ClientFileMapping next = iter.next();
            assertThat(expected.remove(next), is(true));
            next = iter.next();
            assertThat(expected.remove(next), is(true));

            assertThat(iter.hasNext(), is(false));
        }
    }

    @Test
    public void testGetAllFilesSensitive() throws Exception {
        final FileMappingRepo repo = new FileMappingRepo(false);

        final File f1 = new File("f1");
        final FilePath fp1 = createFilePath(f1);
        repo.getByDepotLocation("//depot/file1", fp1);

        final File f2 = new File("f2");
        final FilePath fp2 = createFilePath(f2);
        repo.getByDepotLocation("//depot/file2", fp2);

        final File f3 = new File("f3");
        final FilePath fp3 = createFilePath(f3);
        repo.getByDepotLocation("//depot/FILE1", fp3);

        final Iterable<P4ClientFileMapping> iterable = repo.getAllFiles();
        // ensure the iterable can be used multiple times
        for (int i = 0; i < 3; i++) {
            List<P4ClientFileMapping> expected = new ArrayList<P4ClientFileMapping>(Arrays.asList(
                    new P4ClientFileMapping("//depot/file2", fp2),
                    new P4ClientFileMapping("//depot/file1", fp1),
                    new P4ClientFileMapping("//depot/FILE1", fp3)
            ));

            final Iterator<P4ClientFileMapping> iter = iterable.iterator();
            assertThat(iter.hasNext(), is(true));

            P4ClientFileMapping next = iter.next();
            assertThat(expected.remove(next), is(true));
            next = iter.next();
            assertThat(expected.remove(next), is(true));
            next = iter.next();
            assertThat(expected.remove(next), is(true));

            assertThat(iter.hasNext(), is(false));
        }
    }






    @Test
    public void testGetByDepot() throws Exception {
        fail("not implemented");
    }

    @Test
    public void testGetByLocalFilePath() throws Exception {
        fail("not implemented");
    }

    @Test
    public void testAddLocation() throws Exception {
        fail("not implemented");
    }

    @Test
    public void testUpdateLocation() throws Exception {
        fail("not implemented");
    }

    @Test
    public void testUpdateLocations() throws Exception {
        fail("not implemented");
    }

    @Test
    public void testRefreshFiles() throws Exception {
        fail("not implemented");
    }

    static class RefTestRepo extends FileMappingRepo {
        final Map<P4ClientFileMapping, WeakReference<P4ClientFileMapping>> refs =
                new HashMap<P4ClientFileMapping, WeakReference<P4ClientFileMapping>>();

        RefTestRepo(boolean serverIsCaseInsensitive) {
            super(serverIsCaseInsensitive);
        }

        @NotNull
        WeakReference<P4ClientFileMapping> createRef(@NotNull P4ClientFileMapping map) {
            final WeakReference<P4ClientFileMapping> ret = super.createRef(map);
            refs.put(map, ret);
            return ret;
        }

    }





    private FilePath createFilePath(File f) {
        return new FilePathImpl(f, f.isDirectory());
    }
}