<#--
* Copyright Thoughtworks, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
-->
<div class="console-area" data-console-url="${presenter.consoleoutLocator}" >
<div class="console-action-bar">
    <div>
        <a class="auto-scroll" title="Scroll to bottom">Scroll to end of logs</a>
        <a href="${req.getContextPath()}/files/${presenter.consoleoutLocator}">Raw output</a>
        <a class='toggle-timestamps' href="#"><i class="fas fa-clock"></i> Timestamps</a>
        <a class='toggle-folding' href="#" data-collapsed="true"></a>
        <a id="full-screen">
          <span class="expand">Fullscreen</span>
          <span class="compress">Exit</span>
        </a>
        <a class='change-theme' href="javascript:void(0);">Change theme</a>
    </div>
</div>
<div class="console-log-loading">Waiting for console logs&hellip;</div>
<pre class="buildoutput_pre"></pre>
<div class="console-footer-action-bar">
    <div>
        <a href="javascript:void(0)" class="back-to-top-in-console"><span class="back-to-top-icon"></span>Back to top</a>
    </div>
</div>
<#if presenter.isCompleted()>
<div class="widget" id="build-output-console-warnning" style="display: none;">No console output.</div>
</#if>
</div>
