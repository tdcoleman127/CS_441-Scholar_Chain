# Phase 14, Step 1b-0: infra-engineer plan report (deployment role)

Assembled by the orchestrator from infra-engineer's final report (verbatim below). Step 1b-0 was requested by the human after Step 1a found that package.sh refuses admin-named push profiles and that scholarchain-dev-deployment did not exist (part-a-infra-engineer.md, item 4C, option (ii)). The account ID is redacted as `<account-id>`.

```text
STATUS: COMPLETE

Summary: plan.sh ran without refusing and the plan is exactly the expected one: 2 to add (the deployment role and its inline policy), 0 to change, 0 to destroy. No drift, no stateful resource, no task definition. The trust policy names only the ScholarChainAdmin SSO role. The human can apply the saved plan with the commands below.

STATEFUL REPLACEMENTS OR DESTROYS:
None. plan.sh printed `STATEFUL-ACTION none`, `DESTRUCTIVE none`, and `SECURITY-ACTION none`. The plan has no destroy, replace, or in-place update lines. It also showed no "Objects have changed outside of Terraform" block (grep found 0 matches for "changed outside", "Warning", "task_definition", and "RUNTIME_MODE"), so there is no drift since the Phase 12/13 applies.

OWNER: infra-engineer

WORK ORDER: Phase 14, Step 1b-0 (asked for by the human, before Step 1b). Run `bash scripts/plan.sh dev` to create scholarchain-dev-deployment (option (ii) from the Step 1a report, item 4C). Report the checksum, counts, IAM, ECR/KMS push ability, and cost, and give the human the apply, verify, and profile instructions. No edits, no apply, no AWS changes.

FILES CREATED: none in the repository. Outside the repository: a copy of the full plan output in the session scratchpad (plan-1b0.log; it shows the full account ID; do not paste it into the repository). The plan file itself was written by plan.sh and is git-ignored (*.tfplan): infra/terraform/envs/dev/plans/dev-d9f182f4bd28-20261124T070933Z.tfplan.

FILES MODIFIED: none. `git status --porcelain` after the plan is the same as at the start of the session: the uncommitted Step 1a diff, the human's phase-14 docs edits, and the untracked part-a report and envs/dev/tests/.

COMMANDS RUN (all output seen):
1. `bash scripts/plan.sh dev 2>&1 | tee <scratchpad>/plan-1b0.log`: plan.sh did not refuse. It only reports a dirty tree:
   - `[plan] REVISION git_sha=d9f182f4bd28ed26dec8280b6e19052e0efd2890 tree=DIRTY under infra/terraform (plan reflects uncommitted Terraform)`
   - Identity `assumed-role/AWSReservedSSO_ScholarChainReadOnly_<suffix>`, `ACCOUNT last4=9340 region=us-east-1`.
   - Terraform v1.16.4, provider hashicorp/aws v6.73.0 (from the lock file), S3 backend.
   - Steps 0-5 all OK. fmt OK. "Success! The configuration is valid." PLAN-EXIT 2 (changes present). `RESULT status=OK`.
2. `sha256sum` on the plan file: `f031d9c9ed5a5f827d5774040c603a0e6be7d7800c69c88600ba6b90a3f0e26a`, the same value plan.sh printed.
3. Read-only `aws iam get-role --role-name scholarchain-dev-deployment --profile scholarchain-readonly`: NoSuchEntity. No unmanaged role with this name exists, so the apply will not fail with EntityAlreadyExists.
4. aws-docs lookups (cited below): ECR encryption at rest, ECR GetAuthorizationToken, IAM role chaining, IAM pricing.
5. `git status --porcelain` (unchanged).
Not run: apply, deploy, package, smoke, pipeline, or any AWS write.

TEST RESULTS: No tests in scope (plan only). The Step 1a terraform test results are unchanged.

VALIDATION RESULTS: plan.sh step 1 (`terraform fmt -check -recursive`) OK. Step 3 `terraform validate`: "Success! The configuration is valid." No security scanner was run (checkov, tfsec, and trivy are not installed, per Step 1a). This step changes no code.

PLAN SUMMARY (add/change/destroy, plan checksum):
- Exactly as printed:
  - `[plan] PLAN-FILE infra/terraform/envs/dev/plans/dev-d9f182f4bd28-20261124T070933Z.tfplan`
  - `[plan] PLAN-SHA256 f031d9c9ed5a5f827d5774040c603a0e6be7d7800c69c88600ba6b90a3f0e26a`
  - `[plan] PLAN-SUMMARY Plan: 2 to add, 0 to change, 0 to destroy.`
  - `[plan] ACTION-COUNTS create=2 update=0 destroy=0 replace=0 unparsed=0`
- Resources:
  1. create module.iam.aws_iam_role.deployment[0]
  2. create module.iam.aws_iam_role_policy.deployment[0]
- Outputs: `role_arns.deployment` goes from null to (known after apply). The other 10 outputs are unchanged.
- This matches the expected result exactly. No deviation, no drift.
- runtime_mode: it appears nowhere in the plan. No aws_ecs_task_definition, service, ALB, or endpoint is in the plan, which matches enable_runtime = false. The uncommitted Step 1a diff (the runtime_mode variable) adds no resource actions.

IAM CHANGES (in full, as rendered in the plan; account shown as <account-id>):
- Role scholarchain-dev-deployment:
  - path "/", so the ARN is arn:aws:iam::<account-id>:role/scholarchain-dev-deployment.
  - max_session_duration = 3600.
  - The five standard scholarchain:* cost and owner tags.
  - No managed policies are attached.
- Trust policy: one statement, Allow sts:AssumeRole. Principal.AWS is exactly `arn:aws:iam::<account-id>:role/aws-reserved/sso.amazonaws.com/AWSReservedSSO_ScholarChainAdmin_<suffix>`. That is the single ScholarChainAdmin SSO role ARN with its aws-reserved path. There is no account root, no wildcard, and no other principal. It has no conditions (no MFA or ExternalId); see RISKS.
- Inline policy "deploy-release", 11 statements:
  1. EcrAuthToken: ecr:GetAuthorizationToken on "*". This action has no resource-level permissions.
  2. PushServiceImage: BatchCheckLayerAvailability, InitiateLayerUpload, UploadLayerPart, CompleteLayerUpload, PutImage, BatchGetImage, DescribeImages, DescribeRepositories. Resource: only arn:aws:ecr:us-east-1:<account-id>:repository/scholarchain-dev-services.
  3. TaskDefinitions: ecs:RegisterTaskDefinition, DescribeTaskDefinition, ListTaskDefinitions on "*". These have no resource-level permissions. ListTaskDefinitions is for rollback.sh.
  4. RollServices: ecs:UpdateService and DescribeServices on exactly the two service ARNs: service/scholarchain-dev/scholarchain-dev-api and service/scholarchain-dev/scholarchain-dev-worker.
  5. VerifyRevision: ecs:ListTasks on "*", with the condition ArnEquals ecs:cluster = cluster/scholarchain-dev.
  6. DescribeTasks: ecs:DescribeTasks on task/scholarchain-dev/*.
  7. TargetHealth: elasticloadbalancing:DescribeTargetHealth and DescribeTargetGroups on "*". These are read-only and have no resource-level permissions.
  8. PassTaskRoles: iam:PassRole on exactly scholarchain-dev-ecs-execution, scholarchain-dev-api-task, and scholarchain-dev-worker-task, with the condition StringEquals iam:PassedToService = ecs-tasks.amazonaws.com.
  9. ListArtifacts: s3:ListBucket on arn:aws:s3:::scholarchain-dev-artifacts-<account-id>, with the condition StringLike s3:prefix = artifacts/*.
  10. UploadArtifacts: s3:GetObject and PutObject on scholarchain-dev-artifacts-<account-id>/artifacts/*.
  11. ArtifactKeyViaS3: kms:Decrypt and GenerateDataKey on the project data CMK only (key/<data-key-id>), with the condition StringEquals kms:ViaService = s3.us-east-1.amazonaws.com.
- Scope check:
  - Every item the work order asked about holds as specified.
  - The role has no IAM writes, no ecs:RunTask, no data-bucket, DynamoDB, Neptune, or Secrets Manager access, and no Terraform state access.
- Wider than the push alone needs, but this is by design:
  - For package.sh --push, only statements 1-2 are used. Statements 3-11 are deploy.sh and rollback.sh permissions, because this is the shared deployment role.
  - The "*" resources (statements 1, 3, 5, 7) are only where AWS offers no resource-level permissions, and statement 5 is narrowed by its cluster condition.
  - RegisterTaskDefinition on "*" lets the role register a task definition in any family. It cannot run one (no RunTask), it can update only the two services, and PassRole is limited to the three task roles, so the blast radius stays inside this project.
  - I found nothing wider than the deploy work needs.

CAN THE ROLE PUSH TO THE KMS-ENCRYPTED IMMUTABLE REPOSITORY?
Yes. This is verified from AWS documentation and the Terraform; it has not been tested live.
- The repository has encryption_type KMS with the project CMK (modules/runtime/main.tf:111-112). It has no repository policy (no aws_ecr_repository_policy in the modules).
- AWS docs (https://docs.aws.amazon.com/AmazonECR/latest/userguide/encryption-at-rest.html):
  - When an encrypted repository is created, "Amazon ECR adds an AWS KMS grant to the KMS key with the Amazon ECR repository as the grantee principal".
  - On push, ECR makes the GenerateDataKey request itself under that grant.
  - The page lists caller KMS permissions only for creating a repository (kms:CreateGrant, RetireGrant, DescribeKey) and deleting one (kms:RetireGrant). It lists none for push.
  - So the pushing principal needs no kms permission.
- The key policy (modules/kms/main.tf) has no Deny statement that could block ECR's grant.
- IMMUTABLE means PutImage cannot overwrite an existing tag. That fits Git-SHA tags, and package.sh skips the push when the tag already exists (package.sh:405).
- Every AWS call package.sh makes is covered:
  - sts get-caller-identity needs no permission.
  - ecr describe-repositories, describe-images, get-login-password, and the docker push layer and manifest calls are in statements 1-2.

ESTIMATED HIGH-COST RESOURCES: None. IAM roles and policies have no charge: "AWS Identity and Access Management is provided at no additional charge" (https://docs.aws.amazon.com/location/latest/developerguide/auth-pricing.html; IAM pricing in general, cited from an AWS docs page). STS AssumeRole calls are also free under that page. The ECR image storage after the push is a separate cost and is not part of this plan.

SCRIPT GATES ENCODED: No script changes in this step. Gates that were exercised:
- plan.sh forced the read-only profile, refused static credentials, and printed the account as last-4 only.
- It ran unlocked by design.
- It flagged the dirty tree in its REVISION marker.
- It printed the full plan and classified every action (unparsed=0).
- It exited 0 with status OK because nothing is destroyed.

REQUIRED ENVIRONMENT VARIABLES (names only):
- plan.sh: none. Optional: SCHOLARCHAIN_PLAN_TIMEOUT_SECONDS and SCHOLARCHAIN_PLAN_RECORD_FILE.
- Human apply: AWS_PROFILE (the human's ScholarChainAdmin SSO profile). AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_SESSION_TOKEN must be unset.
- Later push: SCHOLARCHAIN_PUSH_PROFILE (= scholarchain-deploy), SCHOLARCHAIN_AWS_REGION, and SCHOLARCHAIN_ECR_REPOSITORY_NAME.

=====================================================================
HUMAN COMMANDS (Git Bash, run by the human outside Claude)
=====================================================================

A. Apply the saved plan (locking on, admin profile):
    cd "/c/Users/figge/OneDrive/Desktop/UIC Folder/CS 441/CS_441-Scholar_Chain/infra/terraform/envs/dev"
    sha256sum plans/dev-d9f182f4bd28-20261124T070933Z.tfplan
    # must print: f031d9c9ed5a5f827d5774040c603a0e6be7d7800c69c88600ba6b90a3f0e26a
    unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
    export AWS_PROFILE=<your ScholarChainAdmin SSO profile>     # aws sso login first if needed
    terraform apply -input=false plans/dev-d9f182f4bd28-20261124T070933Z.tfplan
    # expect: Apply complete! Resources: 2 added, 0 changed, 0 destroyed.
- .terraform/ is already set up for the S3 backend by plan.sh, so no new init is needed.
- Applying a saved plan takes the state lock by default.
- If state changed after the plan, Terraform refuses the stale plan. In that case, rerun Step 1b-0 rather than applying a fresh plan by hand.

B. Read-only verification after apply:
    aws iam get-role --role-name scholarchain-dev-deployment --profile scholarchain-readonly --region us-east-1 \
      --cli-connect-timeout 10 --cli-read-timeout 30 \
      --query 'Role.{Arn:Arn,Path:Path,MaxSessionDuration:MaxSessionDuration,Trust:AssumeRolePolicyDocument}' --output json
    # expect Path "/", MaxSessionDuration 3600, and one Allow sts:AssumeRole whose Principal.AWS is the
    # ScholarChainAdmin SSO role ARN (aws-reserved path). If it shows an AROA... ID instead, the SSO role was recreated (see RISKS).
    aws iam list-role-policies --role-name scholarchain-dev-deployment --profile scholarchain-readonly --region us-east-1 --output text
    # expect: deploy-release
    aws iam list-attached-role-policies --role-name scholarchain-dev-deployment --profile scholarchain-readonly --region us-east-1 --output text
    # expect: nothing
    aws iam get-role-policy --role-name scholarchain-dev-deployment --policy-name deploy-release \
      --profile scholarchain-readonly --region us-east-1 --query 'PolicyDocument.Statement[].Sid' --output text
    # expect the 11 Sids listed under IAM CHANGES

C. Add this block to ~/.aws/config:
    [profile scholarchain-deploy]
    role_arn = arn:aws:iam::<account-id>:role/scholarchain-dev-deployment
    source_profile = <your ScholarChainAdmin SSO profile>
    role_session_name = scholarchain-package
    duration_seconds = 3600
    region = us-east-1
- Changes from the proposed block:
  - role_arn is confirmed correct (path "/", name scholarchain-dev-deployment).
  - I added role_session_name (optional) so CloudTrail shows who pushed.
  - I set duration_seconds = 3600 explicitly. It cannot be higher: the role's max_session_duration is 3600, and SSO-role to IAM-role is role chaining, which "is limited to one hour, regardless of the maximum session duration" (https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_use_switch-role-console.html).
- The 1-hour limit does not block package.sh:
  - Every AWS call in package.sh is a separate CLI run (package.sh:291).
  - The CLI renews the assumed-role credentials from the source profile when they expire, so a long sbt build (up to 2400 s) and docker build (up to 1200 s) before the push are fine as long as the SSO login is still valid.
  - get-login-password runs right before docker push (package.sh:414-417). ECR tokens are valid for 12 hours (https://docs.aws.amazon.com/AmazonECR/latest/APIReference/API_GetAuthorizationToken.html).
- The profile name "scholarchain-deploy" passes package.sh's checks: no "admin", not the read-only profile, and it matches `^[A-Za-z0-9._-]{1,64}$`. package.sh checks only the push profile's name, not source_profile.
- Assume test:
    aws sso login --profile <your ScholarChainAdmin SSO profile>
    aws sts get-caller-identity --profile scholarchain-deploy --query Arn --output text
    # expect: arn:aws:sts::<account-id>:assumed-role/scholarchain-dev-deployment/scholarchain-package
    aws ecr describe-repositories --repository-names scholarchain-dev-services --profile scholarchain-deploy \
      --region us-east-1 --query 'repositories[0].[repositoryName,imageTagMutability]' --output text
    # expect: scholarchain-dev-services  IMMUTABLE   (proves the role's ECR read before you push)
- Then follow Step 1a item 4C with `export SCHOLARCHAIN_PUSH_PROFILE=scholarchain-deploy`. The tree must be clean first.

ASSUMPTIONS:
- The ScholarChainAdmin permission set allows sts:AssumeRole on this role. The trust policy names the role ARN, not a session, so for a role session the caller's identity policy must also allow the call. I assume this from the set's admin name; it is unverified.
- Your local dev.tfvars has exactly the values the work order describes. I did not read it; the plan output agrees with those values.

RISKS:
- Plan provenance: the plan was made at d9f182f with a DIRTY infra/terraform tree (the uncommitted Step 1a runtime_mode diff). That diff adds no actions here (enable_runtime = false). For a clean audit trail, commit Step 1a after review; the saved plan still applies as it is.
- The trust policy depends on the SSO role's unique ID. If IAM Identity Center reprovisions the ScholarChainAdmin role (for example, the permission set is deleted and recreated), the trust policy stops working. It would then show a bare AROA ID, and the principal ARN would need to be re-applied.
- The trust policy has no condition (MFA, aws:SourceIdentity). It relies on SSO sign-in for MFA. Adding conditions is optional hardening that I did not make.
- Unverified edge case: whether an ECR token obtained with role-chained credentials outlives those credentials. Not confirmed in the docs. If a very slow push (close to package.sh's 1200 s push timeout) fails with an auth error, rerun package.sh. The tag is immutable and package.sh skips a tag that already exists.
- The plan output and the scratchpad log contain the full account ID. Do not paste them into repository files. This report uses <account-id> and <suffix>.

MANUAL ACTION REQUIRED (human):
1. Sign off on the plan (checksum f031d9c9...e26a, 2 add / 0 change / 0 destroy, IAM as above).
2. Run A (apply) with your admin profile outside Claude.
3. Run B (read-only verify).
4. Add the C profile block and run the assume test.
5. Then run the image push from Step 1a item 4C (clean tree required) and continue to Step 1b.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: code-reviewer reviews this plan output. Then the human signs off and applies the saved plan (checksum f031d9c9ed5a5f827d5774040c603a0e6be7d7800c69c88600ba6b90a3f0e26a), runs the read-only verification and the assume test, and pushes the bootstrap image with SCHOLARCHAIN_PUSH_PROFILE=scholarchain-deploy. After that, Step 1b: infra-engineer runs plan.sh dev with enable_runtime = true.
```
