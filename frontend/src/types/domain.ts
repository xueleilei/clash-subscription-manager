export type Id = string
export type Version = string
export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue }
export type JsonObject = { [key: string]: JsonValue }
export interface Diagnostic { severity: 'ERROR' | 'WARNING'; code: string; path: string; line: number | null; column: number | null; message: string }
export interface ValidationReport { errors: Diagnostic[]; warnings: Diagnostic[] }
export interface User { id: Id; username: string }
export interface Credentials { username: string; password: string }
export interface TargetRef { kind: 'NODE' | 'GROUP' | 'DIRECT' | 'REJECT' | 'UNRESOLVED'; id: Id | null; unresolvedName: string | null }
export interface GroupDefinition { id: Id; name: string; type: string; members: TargetRef[]; options: JsonObject }
export interface RuleDefinition { id: Id; expression: { type: string; value: string | null; providerId: Id | null }; target: TargetRef; modifiers: string[]; rawText: string | null; issues: Diagnostic[] }
export interface ProviderDefinition { id: Id; name: string; type: string; url: string | null; behavior: string; format: string; interval: number; options: JsonObject }
export interface ProfileDocument { schemaVersion: number; baseYaml: string; groups: GroupDefinition[]; rules: RuleDefinition[]; providers: ProviderDefinition[]; importIssues: Diagnostic[] }
export interface DraftView { profileId: Id; version: Version; document: ProfileDocument; diagnostics: ValidationReport }
export interface ProfileView { id: Id; name: string; version: Version; draftVersion: Version; currentReleaseId: Id | null; publishedVersion: Version | null; draftDefinitionHash: string; publishedDefinitionHash: string | null; createTime: string; updateTime: string }
export interface NodeSummary { id: Id; sourceId: Id | null; originalName: string; protocol: string; region: string | null; snapshotVersion: Version }
export interface NodeSpec extends Pick<NodeSummary, 'id' | 'sourceId' | 'originalName' | 'protocol'> { connection: JsonObject; dialerRef: TargetRef | null }
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export type InputFormat = 'AUTO' | 'CLASH_YAML' | 'URI_LIST' | 'BASE64'
export interface SourceView { id: Id; name: string; configVersion: Version; snapshotVersion: Version; enabled: boolean; refreshIntervalSeconds: number; inputFormat: InputFormat; hostHint: string; syncStatus: string; lastAttemptTime: string | null; lastSuccessTime: string | null; nextRefreshTime: string | null; lastErrorCode: string | null }
export interface SourceRequest { url: string; headers: Record<string, string>; userAgent: string; outboundProxy: string }
export interface SourceCommand { id?: Id; expectedVersion?: Version; name: string; enabled: boolean; refreshIntervalSeconds: number; inputFormat: InputFormat; url?: string; headers?: Record<string, string>; userAgent?: string; outboundProxy?: string }
export interface ChangeSummary { added: number; updated: number; removed: number; profiles: Record<Id, { removedMembers: number; rejectedRuleIds: Id[]; emptyGroupIds: Id[] }> }
export interface TaskView { id: Id; sourceId: Id; status: string; trigger: string; createdAt: string; updatedAt: string; outcome: null | { status: string; decisionId: Id | null; changes: ChangeSummary; report: ValidationReport } }
export interface EmptyDecision { id: Id; sourceId: Id; configVersion: Version; snapshotVersion: Version; state: string; createdAt: string }
export interface RemovalPreview { id: Id; ownerRevision: Version; expiresAt: string; sourceId: Id; nodeCount: number; affectedProfiles: Id[] }
export interface ReleaseView { id: Id; profileId: Id; version: Version; definitionHash: string; yamlDigest: string; nodeCount: number; reason: string; createdAt: string }
export interface PublishPreview { profileId: Id; expectedVersion: Version; definitionHash: string; yamlDigest: string; yaml: string | null; nodeCount: number; groupCount: number; ruleCount: number; emptyGroupIds: Id[]; sourceVersions: Record<Id, Version>; report: ValidationReport }
export interface SubscriptionLinkData { profileId: Id; url: string; token: string }
