ScholarChain Questions
Hello Professor, I'm working on the project individually and have the build setup done. Before the cloud phases I have three questions:

1. Does the course provide AWS access or credits, or should I use my own AWS account?

2. Is Amazon Bedrock allowed for the RAG part, and is it covered by credits?

3. If the full dataset sizes cost too much, may I use a documented smaller subset and explain it in my report?
 

You may use Amazon Bedrock and smaller datasets for this project, provided that you carefully control your AWS expenses, document your design decisions, and satisfy the project's technical and experimental requirements.
AWS account policy. Every student is required to use their own AWS account for the project. This requirement applies equally to students working individually and those working in teams. The university and the course do not provide AWS accounts, shared AWS infrastructure, or guaranteed AWS credits for this assignment. You are responsible for creating and maintaining your account, configuring the necessary services, securing your credentials, monitoring resource utilization, and paying all charges incurred. You must not assume that the instructor or university will provide access to AWS resources or reimburse expenses. If you have AWS promotional credits, you may use them, subject to their eligibility restrictions and expiration dates.
Amazon Bedrock. Yes, you may use Amazon Bedrock for the RAG component. However, Bedrock is a metered service, and whether its charges are covered by AWS credits depends on the terms of those credits. You must verify the applicable pricing and credit eligibility before enabling the service.
Dataset sizes. Yes, you may use smaller, representative subsets of the datasets to control costs. However, you must document your selection criteria, actual dataset sizes, and any resulting limitations. Your experiments must still demonstrate the scalability of your distributed implementation by evaluating its performance at different dataset sizes.
Regarding the $25 spending limit, this amount is a recommended maximum budget for the entire project, not a required expenditure, a university-provided credit, or an allowance that resets for each experiment. It applies to your cumulative AWS usage across all project phases, including computing, storage, networking, data transfer, and Amazon Bedrock. You are not expected to spend $25, and spending more will not improve your grade. If an experiment would exceed this budget, reduce its scope, select less expensive resources, or use smaller datasets. The $25 recommendation is not an AWS-enforced spending cap.
 
I should add a warning about AWS costs: You are solely responsible for all AWS charges, including unexpected costs from misconfigured or unused resources. AWS Budgets and billing alerts do not automatically stop charges. Monitor your spending, configure alerts, and delete resources when they are no longer needed. Neither the instructor nor the university will reimburse cost overruns. Remember, there is no free lunch in cloud computing: AWS elastically scales resources for your applications, and your bill can scale just as elastically. Unfortunately, your student budget does not come with automatic scaling!
 
Before proceeding, please complete the following checklist:
Create or verify your own AWS account and confirm whether any promotional credits are available.
Establish a cumulative project budget of no more than $25 and configure AWS Budgets alerts at $10, $15, and $20.
Check Amazon Bedrock pricing, model availability, and credit eligibility before enabling it.
Select appropriately sized datasets and document the selection criteria and actual record counts.
Plan experiments using multiple dataset sizes to demonstrate scalability and measure performance.
Monitor AWS charges throughout your experiments and immediately terminate or delete unused resources.
Include your cloud architecture, experimental results, dataset limitations, and actual AWS expenses in your final report.
The primary objective of this project is to demonstrate your understanding of cloud computing concepts, distributed processing, and system design. You will be evaluated on the correctness of your implementation, the quality of your experiments, and your analysis of the results rather than the amount of AWS resources consumed.