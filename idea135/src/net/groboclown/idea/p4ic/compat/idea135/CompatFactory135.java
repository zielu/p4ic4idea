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

package net.groboclown.idea.p4ic.compat.idea135;

import net.groboclown.idea.p4ic.compat.CompatFactory;
import net.groboclown.idea.p4ic.compat.CompatManager;

public class CompatFactory135 implements CompatFactory {
    @Override
    public String getMinCompatibleApiVersion() {
        return "135.1286";
    }

    @Override
    public String getMaxCompatibleApiVersion() {
        return "136";
    }

    @Override
    public CompatManager createCompatManager() throws IllegalStateException {
        return new CompatManager135();
    }
}
