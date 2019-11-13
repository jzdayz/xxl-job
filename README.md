# 主题
学习xxl-job的代码

# 模块
- admin 调度器，发送rpc给执行器，进行任务调度，可以部署多台，在多台的情况下，利用数据库排他锁，避免重复调度
- client 执行器，接收调度执行具体业务代码


# 思路
> xxl-job 主要依赖于数据库，进行任务的crud，查询调度，同时利用数据库排他锁避免重复调度的问题，也就是说可以部署多台，类似于多主，都会执行，但是利用数据库的数据去判断是否应该执行，比如两台admin，A和B，一个任务是每天4点去调度任务，如果A先拿到锁，那么A就去调度执行器执行，然后B去调度的时候，发现任务已经执行过了，就不会去执行，所以是多主的概念，依赖数据库

