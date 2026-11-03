import { readFile } from 'node:fs/promises'
import { test,expect } from '@playwright/test'
import { api,digest,draft,link,login,nodes,publishUi,ready,remember,restartApplication,subscription } from './helpers'
import type { ConfigImportPreview, ProfileView } from '../src/types/domain'
test('full file import, explicit local binding, republish and application restart preserve credentials and stable subscription',async({page})=>{
 await login(page);const yaml=`mixed-port: 7890\nproxies:\n  - {name: 验收本地, type: ss, server: import.example.invalid, port: 443, cipher: aes-128-gcm, password: acceptance-fixture-only}\nproxy-groups:\n  - {name: 本地组, type: select, proxies: [验收本地]}\nrules:\n  - 'MATCH,本地组'\n`
 await page.getByRole('link',{name:/导入完整配置/}).click();await page.getByLabel('完整配置文件',{exact:true}).setInputFiles({name:'import.yaml',mimeType:'application/yaml',buffer:Buffer.from(yaml)});await page.getByRole('button',{name:'解析并预览',exact:true}).click();await expect(page.getByLabel('验收本地 的导入方式',{exact:true})).toHaveValue('LOCAL');await page.getByRole('button',{name:'创建新方案草稿',exact:true}).click();await expect(page).toHaveURL(/\/profiles\/[a-f0-9-]+$/);const id=page.url().split('/').at(-1)!,profile=(await api<ProfileView[]>(page,'/profiles')).find(p=>p.id===id)!,output=await publishUi(page,'1'),url=await link(page,id);expect(digest((await subscription(page,url)).text)).toBe(digest(output))
 // Independent import does not alter any existing profile.
 const profileCount=(await api<ProfileView[]>(page,'/profiles')).length;expect(profileCount).toBeGreaterThanOrEqual(1)
 const remote=(await nodes(page)).find(node=>node.sourceId)!;if(!remote)throw new Error('Lifecycle fixtures must provide an upstream node')
 await page.getByRole('link',{name:/统一节点池/}).click();await page.getByLabel('搜索节点名称',{exact:true}).fill('验收本地');await page.getByRole('button',{name:'搜索',exact:true}).click();await page.getByRole('button',{name:'关联上游',exact:true}).click();await page.locator(`[data-remote-node="${remote.id}"]`).check();await page.getByRole('button',{name:'预览关联影响',exact:true}).click();await expect(page.getByRole('dialog')).toContainText(profile.name);await page.getByRole('button',{name:'确认关联到草稿',exact:true}).click();await expect(page.getByRole('dialog')).toBeHidden();expect(digest((await subscription(page,url)).text)).toBe(digest(output))
 await page.getByRole('link',{name:profile.name,exact:true}).click();const next=await publishUi(page,'2');expect(digest(next)!==digest(output)).toBe(true);expect((await link(page,id))===url).toBe(true)
 const tokenDigest=digest(url),draftDigest=digest(JSON.stringify((await draft(page,id)).document));restartApplication();await ready(page);await login(page);expect(digest(await link(page,id))).toBe(tokenDigest);expect(digest((await subscription(page,url)).text)).toBe(digest(next));expect(digest(JSON.stringify((await draft(page,id)).document))).toBe(draftDigest)
 // Expire the real server session while a modal contains unapplied input.
 await page.getByRole('link',{name:profile.name,exact:true}).click();await page.getByRole('button',{name:'＋ 新建代理组',exact:true}).click();await page.getByLabel('代理组名称',{exact:true}).fill('重认证后保留的未完成输入')
 await api(page,'/session','DELETE');await expect(page.getByText('会话已过期，请重新登录',{exact:true})).toBeVisible({timeout:25000})
 await page.getByLabel('用户名',{exact:true}).fill('acceptance-admin');await page.getByLabel('密码',{exact:true}).fill((await readFile(process.env.E2E_PASSWORD_FILE!,'utf8')).trim());await page.getByRole('button',{name:'登录工作空间',exact:true}).click()
 await expect(page.getByLabel('代理组名称',{exact:true})).toBeVisible();await expect(page.getByLabel('代理组名称',{exact:true})).toHaveValue('重认证后保留的未完成输入');await expect(page).toHaveURL(new RegExp(`/profiles/${id}$`));await page.getByRole('button',{name:'取消',exact:true}).click()
 await remember(page,id)
})
