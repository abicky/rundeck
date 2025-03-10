%{--
  - Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
  -
  - Licensed under the Apache License, Version 2.0 (the "License");
  - you may not use this file except in compliance with the License.
  - You may obtain a copy of the License at
  -
  -     http://www.apache.org/licenses/LICENSE-2.0
  -
  - Unless required by applicable law or agreed to in writing, software
  - distributed under the License is distributed on an "AS IS" BASIS,
  - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  - See the License for the specific language governing permissions and
  - limitations under the License.
  --}%

<%@ page import="org.rundeck.core.auth.AuthConstants" %>
<%--
 Copyright 2010 DTO Labs, Inc. (http://dtolabs.com)

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 --%>
<%--
  User: greg
  Date: Feb 2, 2010
  Time: 3:08:28 PM
--%>
<tr class="${index!=null && (index%2)==1?'alternateRow':''}">
    <td  style="width:16px">
        <g:expander key="udetail_${user.login}" text=""/>
    </td>
    <td>
        <span class="userlogin" >
            <g:enc>${user.login}</g:enc>
        </span>
        <span class="username" >
            <g:enc>${user.firstName} ${user.lastName}</g:enc>
        </span>
        <span class="useremail">
            <g:if test="${user.email}">
                &lt;<g:enc>${user.email}</g:enc>&gt;
            </g:if>
        </span>

        <g:set var="adminauth" value="${auth.resourceAllowedTest(kind:AuthConstants.TYPE_USER,action:[AuthConstants.ACTION_ADMIN],context:AuthConstants.CTX_APPLICATION)}"/>
        <g:if test="${adminauth}">
        <span class="useredit">
            <g:link action="edit" params="[login:user.login]" class="textbtn textbtn-info textbtn-on-hover">
                <i class="glyphicon glyphicon-edit"></i> edit
            </g:link>
        </span>
        </g:if>
    </td>
</tr>
<tr class="${index!=null && (index%2)==1?'alternateRow':''}" id="udetail_${enc(attr:user.login)}" style="display:none">
    <td></td>
    <td >
        <tmpl:user user="${user}" expandAccess="${true}"/>
    </td>
</tr>
