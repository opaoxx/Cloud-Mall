import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import org.apache.jmeter.assertions.AssertionResult
import org.apache.jmeter.protocol.http.control.Header
import org.apache.jmeter.protocol.http.control.HeaderManager
import org.apache.jorphan.util.JMeterStopThreadException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TradeSteadyPostAbort extends RuntimeException {
    TradeSteadyPostAbort() { super('TRADE_POST_ABORT', null, false, false) }
}

class ShortSteadyClock {
    final String runId
    final String outputDir
    final long setupBudgetMillis
    final long setupStartedNanos = System.nanoTime()
    final long setupStartedEpochMillis = System.currentTimeMillis()
    final long setupDeadlineNanos
    final AtomicBoolean abort = new AtomicBoolean(false)
    final AtomicInteger warmFinished = new AtomicInteger(0)
    final AtomicLong t0 = new AtomicLong(Long.MIN_VALUE)
    final AtomicBoolean startPublished = new AtomicBoolean(false)
    final AtomicBoolean blockedPublished = new AtomicBoolean(false)
    final AtomicBoolean endPublished = new AtomicBoolean(false)
    ShortSteadyClock(String runId, String outputDir, long budget) {
        this.runId = runId; this.outputDir = outputDir; this.setupBudgetMillis = budget
        this.setupDeadlineNanos = setupStartedNanos + budget * 1000000L
    }
}

// Invoked only by the sibling JMX. No business request is sent from this script.
def parts = Parameters.trim().split(/\s+/)
def action = parts[0]
def stage = parts.length > 1 ? parts[1] : action
def s = vars.getObject('trade.state')
def shared = vars.getObject('trade.steadyClock')
def stageAbort = shared instanceof ShortSteadyClock ? shared.abort : null
def result = action == 'POST' ? prev : (binding.hasVariable('SampleResult') ? SampleResult : null)
def hash = { byte[] raw -> MessageDigest.getInstance('SHA-256').digest(raw).encodeHex().toString() }
def writeNew = { String name, Object value ->
    byte[] raw = JsonOutput.toJson(value).getBytes('UTF-8')
    def path = Paths.get(s.outputDir).resolve(name)
    Files.write(path, raw, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    if (!Arrays.equals(Files.readAllBytes(path), raw)) throw new IllegalStateException('SAFE_READBACK_FAILED')
}
def safe = { Object value ->
    if (!(value instanceof Map)) return null
    def keys = ['userId','balance','id','skuId','productId','orderNo','payNo','status','amount','unitPrice',
                'payAmount','totalAmount','createdAt','quantity','lineAmount','availableQuantity']
    def v = [:]
    keys.each { if (value.containsKey(it)) v[it] = value[it] }
    if (value.items instanceof List) v.items = value.items.collect { item ->
        ['productId','skuId','quantity','unitPrice','lineAmount'].collectEntries { key -> [(key):item[key]] }
    }
    return v
}
def exportState = { ->
    def v = [schemaVersion:1,scope:'JMETER_SHORT_STEADY_SLOT_HTTP_ONLY',profile:'SHORT_STEADY_BOUNDED',threadCount:5,workerIndex:s.workerIndex,slotIndex:s.slotIndex,phase:s.phase,iterationCount:1]
    ['runId','parentRunId','username','userId','addressId','skuId','productId','price','balanceBefore','orderNo','payNo',
     'orderKey','payKey','callbackId','availableBefore','availableFinal','rootAvailableBefore','plannedReleaseOffsetMillis','actualReleaseOffsetMillis','slotStatus','issued','pollIterations','requests','finalViews'].each { v[it] = s[it] }
    v
}
def finish = { boolean ok, String code ->
    if (s != null && !s.finished) {
        s.finished = true
        def v = exportState()
        v.httpPassed = ok
        v.status = ok ? 'HTTP_FUNCTIONAL_OBSERVED_PENDING_SQL' : (s.slotStatus=='SKIPPED'?'SKIPPED':'HTTP_FUNCTIONAL_FAILED_OR_UNKNOWN')
        v.errorCode = code
        v.completedEpochMillis = System.currentTimeMillis()
        v.finishedOffsetMillis = s.phase=='MEASUREMENT' && shared.t0.get()!=Long.MIN_VALUE ? Math.floorDiv(System.nanoTime()-shared.t0.get(),1000000L) : null
        v.slotStatus = ok ? 'HTTP_TERMINAL' : (s.slotStatus=='SKIPPED' ? 'SKIPPED' : 'UNKNOWN')
        v.sqlConsistencyProven = false
        writeNew('finish.json', v)
        if (ok && s.phase=='WARMUP') shared.warmFinished.incrementAndGet()
        s.password=null; s.token=null
    }
}
def abort = { String code ->
    if (stageAbort instanceof AtomicBoolean) stageAbort.set(true)
    vars.put('trade.abort','true')
    vars.put('trade.poll','false')
    if (result != null) {
        result.setSuccessful(false)
        result.setResponseMessage(code)
        def failure = new AssertionResult('trade-short-steady')
        failure.setFailure(true); failure.setFailureMessage(code); result.addAssertionResult(failure)
    }
    if (action=='POST' && s!=null && !s.httpRecordWritten) {
        try {
            s.httpIndex=(s.httpIndex ?: 0)+1
            writeNew(String.format('http-%04d-%s.json',s.httpIndex,stage),[schemaVersion:1,stage:stage,label:stage,
                httpStatus:prev.getResponseCode()==~ /[0-9]{3}/ ? prev.getResponseCode() : 'NETWORK_UNKNOWN',
                apiCode:'UNKNOWN',success:false,errorCode:code,elapsedMillis:prev.getTime(),phase:s.phase,
                slotIndex:s.slotIndex,workerIndex:s.workerIndex,userId:s.userId,addressId:s.addressId,
                orderNo:s.orderNo,payNo:s.payNo,skuId:s.skuId,amount:s.price,requestBodySha256:s.requestSafeHash,
                requestStartedOffsetMillis:s.phase=='MEASUREMENT'?Math.floorDiv(s.requestStartedNanos-shared.t0.get(),1000000L):null,
                requestStartBoundary:'PRE_SAMPLER_DISPATCH_NANO',sampleStartedEpochMillis:prev.getStartTime()])
            s.httpRecordWritten=true
        } catch (Throwable ignored) { /* Missing safe evidence remains failure. */ }
    }
    try { finish(false, code) } catch (Throwable ignored) { /* Never overwrite a foreign/old finish file. */ }
    ctx.getEngine().stopTest(false)
    ctx.getThread().stop()
    // PostProcessor must return normally so the failed HTTP sample reaches JTL.
    if (action == 'POST') throw new TradeSteadyPostAbort()
    throw new JMeterStopThreadException(code)
}
def check = { boolean condition, String code -> if (!condition) abort(code) }
def id = { Object value ->
    check((value instanceof Integer || value instanceof Long || value instanceof BigInteger || value instanceof String)
        && value.toString() ==~ /[1-9][0-9]{0,18}/, 'TRADE_ID_INVALID')
    try { return new BigInteger(value.toString()).longValueExact() }
    catch (Throwable ignored) { abort('TRADE_ID_RANGE') }
}
def money = { Object value ->
    check(value instanceof String && value ==~ /(?:0|[1-9][0-9]*)(?:\.[0-9]{1,2})?/, 'TRADE_MONEY_INVALID')
    new BigDecimal(value)
}
def clearRaw = { sample ->
    if (sample != null) {
        long bytes = sample.getBytesAsLong()
        sample.setSamplerData(''); sample.setRequestHeaders(''); sample.setResponseHeaders('')
        sample.setResponseData(new byte[0]); sample.setBytes(bytes)
    }
}

try {
    if (action == 'INIT') {
        if (props.getProperty('cloudmall.execute','false') != 'true') {
            vars.put('trade.abort','true'); result.setSuccessful(true); result.setResponseCode('200')
            result.setResponseMessage('DISABLED'); ctx.getThread().stop(); return
        }
        String runId = vars.get('runId')
        check(runId != null && runId ==~ /ss_[0-9a-f]{10}/,'TRADE_STEADY_RUN_ID_INVALID')
        check(ctx.getThreadGroup().getNumThreads()==5 && ctx.getThreadNum() in 0..4 && vars.getIteration() in 1..4,'TRADE_STEADY_SHAPE_INVALID')
        check(!props.containsKey('cloudmall.threads') || props.getProperty('cloudmall.threads')=='5','TRADE_STEADY_SHAPE_INVALID')
        check(!props.containsKey('cloudmall.loops') || props.getProperty('cloudmall.loops')=='4','TRADE_STEADY_SHAPE_INVALID')
        check(vars.get('profile')=='SHORT_STEADY_BOUNDED','TRADE_PROFILE_INVALID')
        ['response_data','response_data.on_error','samplerData','requestHeaders','responseHeaders'].each {
            check(!props.getProperty('jmeter.save.saveservice.'+it,'false').equalsIgnoreCase('true'),'TRADE_UNSAFE_JTL_CONFIG')
        }
        check(props.getProperty('sample_variables','').trim().isEmpty(),'TRADE_UNSAFE_SAMPLE_VARIABLES')
        check(System.getenv('JMETER_TRADE_PASSWORD') != null && !System.getenv('JMETER_TRADE_PASSWORD').isBlank(),'TRADE_PASSWORD_ENV_MISSING')
        check(System.getenv('CLOUDMALL_INTERNAL_CALLBACK_TOKEN') != null && !System.getenv('CLOUDMALL_INTERNAL_CALLBACK_TOKEN').isBlank(),'TRADE_CALLBACK_TOKEN_ENV_MISSING')
        check(vars.get('host') in ['localhost','127.0.0.1'],'TRADE_HOST_INVALID')
        def directory = Paths.get(vars.get('outputDir') ?: '')
        check(directory.isAbsolute() && Files.isDirectory(directory) && !Files.isSymbolicLink(directory),'TRADE_OUTPUT_DIR_INVALID')
        ['port','stockPort','payPort'].each { check(vars.get(it) ==~ /[1-9][0-9]{0,4}/ && vars.get(it).toInteger()<=65535,'TRADE_PORT_INVALID') }
        check(vars.get('setupBudgetMillis') ==~ /[1-9][0-9]{0,5}/,'TRADE_SETUP_BUDGET_INVALID')
        long setupBudget = new BigInteger(vars.get('setupBudgetMillis')).longValueExact()
        check(setupBudget<=120000L,'TRADE_SETUP_BUDGET_INVALID')
        def candidate = new ShortSteadyClock(runId,directory.normalize().toString(),setupBudget)
        def previous = props.putIfAbsent('trade.steady.clock.'+runId,candidate)
        shared = previous==null ? candidate : previous
        check(shared instanceof ShortSteadyClock && shared.runId==runId && shared.outputDir==directory.normalize().toString()
            && shared.setupBudgetMillis==setupBudget,'TRADE_SHARED_CLOCK_INVALID')
        vars.putObject('trade.steadyClock',shared);stageAbort=shared.abort
        check(!stageAbort.get(),'TRADE_STAGE_ABORTED')
        check(vars.get('expectedAvailable')=='9991','TRADE_ROOT_STOCK_BASELINE_INVALID')
        int worker = ctx.getThreadNum()
        int iteration = vars.getIteration()
        boolean warm = iteration==1
        int round = iteration-2
        int slot = warm ? worker+1 : 6+round*5+worker
        String slotRun = runId+String.format('_s%02d',slot)
        check(('jmeter_trade_'+slotRun).length()<=32,'TRADE_USERNAME_LENGTH')
        if (!warm) {
            boolean released=shared.startPublished.get()
            while (!released && !stageAbort.get() && System.nanoTime()<shared.setupDeadlineNanos) {
                def go=directory.resolve('measurement-go.json')
                if (shared.warmFinished.get()==5 && Files.isRegularFile(go) && !Files.isSymbolicLink(go)) {
                    check(Files.size(go)<=4096,'TRADE_GO_INVALID')
                    def decision
                    try { decision=new JsonSlurper().parseText(new String(Files.readAllBytes(go),'UTF-8')) }
                    catch (Throwable ignored) { abort('TRADE_GO_INVALID') }
                    check(decision instanceof Map && decision.keySet()==(['execute','runId'] as Set)
                        && decision.execute instanceof Boolean && decision.runId==runId,'TRADE_GO_INVALID')
                    if (!decision.execute) {
                        if (shared.blockedPublished.compareAndSet(false,true)) {
                            byte[] blocked=JsonOutput.toJson([schemaVersion:1,runId:runId,status:'CAPABILITY_BLOCKED',warmupHTTPCompletedCount:5,issuedMeasurementSlots:0]).getBytes('UTF-8')
                            Files.write(directory.resolve('measurement-blocked.json'),blocked,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
                        }
                        vars.put('trade.slot.issue','false');result.setSuccessful(true);result.setResponseCode('200');result.setResponseMessage('CAPABILITY_BLOCKED');ctx.getThread().stop();return
                    }
                    long proposed=System.nanoTime()
                    if (shared.t0.compareAndSet(Long.MIN_VALUE,proposed)) {
                        def marker=[schemaVersion:1,runId:runId,origin:'JMETER_NANO',startedEpochMillis:System.currentTimeMillis(),
                            setupStartedEpochMillis:shared.setupStartedEpochMillis,setupElapsedMillis:Math.floorDiv(proposed-shared.setupStartedNanos,1000000L),
                            setupBudgetMillis:setupBudget,measurementDurationMillis:60000,drainDeadlineOffsetMillis:120000,
                            workerCount:5,plannedMeasurementSlots:15,releaseOffsetsMillis:(0..14).collect { it*4000 }]
                        byte[] data=JsonOutput.toJson(marker).getBytes('UTF-8');def path=directory.resolve('measurement-start.json')
                        Files.write(path,data,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
                        check(Arrays.equals(Files.readAllBytes(path),data),'TRADE_MEASUREMENT_START_INVALID')
                        shared.startPublished.set(true)
                    }
                    while (!shared.startPublished.get() && !stageAbort.get() && System.nanoTime()<shared.setupDeadlineNanos) Thread.sleep(10)
                    released=shared.startPublished.get()
                }
                if (!released) Thread.sleep(50)
            }
            check(released && !stageAbort.get(),'TRADE_SETUP_OR_CAPABILITY_DEADLINE')
        }
        def slotDirectory=directory.resolve(String.format('slot-%02d',slot));Files.createDirectory(slotDirectory)
        s=[outputDir:slotDirectory.toString(),runId:slotRun,parentRunId:runId,workerIndex:worker+1,slotIndex:slot,
            phase:warm?'WARMUP':'MEASUREMENT',username:'jmeter_trade_'+slotRun,rootAvailableBefore:9991,
            plannedReleaseOffsetMillis:warm?null:worker*4000+round*20000,actualReleaseOffsetMillis:null,
            slotStatus:'PLANNED',issued:false,skuId:id(vars.get('skuId')),orderKey:'jmeter-order-'+slotRun,
            payKey:'jmeter-pay-'+slotRun,callbackId:'jmeter-callback-'+slotRun,requests:[:],finalViews:[:],pollIterations:0,finished:false]
        vars.putObject('trade.state',s);vars.put('trade.abort','false');vars.put('trade.slot.issue','false')
        writeNew('run-state.json',exportState())
        if (!warm) {
            long scheduled=shared.t0.get()+s.plannedReleaseOffsetMillis*1000000L
            while (System.nanoTime()<scheduled && !stageAbort.get()) {
                long waitMillis=Math.max(1L,Math.min(50L,Math.floorDiv(scheduled-System.nanoTime(),1000000L)))
                Thread.sleep(waitMillis)
            }
            check(!stageAbort.get(),'TRADE_STAGE_ABORTED')
            long now=System.nanoTime();long offset=Math.floorDiv(now-shared.t0.get(),1000000L)
            if (now-shared.t0.get()>=60000000000L || now-scheduled>=2000000000L) {
                s.slotStatus='SKIPPED';s.actualReleaseOffsetMillis=offset
                writeNew('slot-skip.json',[schemaVersion:1,runId:slotRun,slotIndex:slot,workerIndex:worker+1,phase:'MEASUREMENT',
                    issued:false,plannedReleaseOffsetMillis:s.plannedReleaseOffsetMillis,actualReleaseOffsetMillis:offset,
                    reason:now-shared.t0.get()>=60000000000L?'MEASUREMENT_WINDOW_CLOSED':'LATE_OR_PREVIOUS_SLOT_BUSY'])
                finish(false,'TRADE_SLOT_SKIPPED');result.setSuccessful(true);result.setResponseCode('200');result.setResponseMessage('SKIPPED');return
            }
            s.actualReleaseOffsetMillis=offset
        } else check(System.nanoTime()<shared.setupDeadlineNanos,'TRADE_SETUP_DEADLINE')
        s.slotStatus='ISSUED';s.issued=true;vars.put('trade.slot.issue','true')
        writeNew('slot.go',[schemaVersion:1,runId:slotRun,slotIndex:slot,workerIndex:worker+1,phase:s.phase,
            plannedReleaseOffsetMillis:s.plannedReleaseOffsetMillis,actualReleaseOffsetMillis:s.actualReleaseOffsetMillis])
        writeNew('run.once',[schemaVersion:1,profile:'SHORT_STEADY_BOUNDED',runId:slotRun,slotIndex:slot,workerIndex:worker+1,phase:s.phase,skuId:s.skuId])
        s.password = hash((System.getenv('JMETER_TRADE_PASSWORD')+':'+slotRun).getBytes('UTF-8')).substring(0,32)
        result.setResponseCode('200');result.setSuccessful(true);return
    }
    check(s != null && vars.get('trade.abort') != 'true','TRADE_ABORTED')
    // An in-flight response may still reveal committed IDs after another VU failed.
    if (action!='POST') check(stageAbort instanceof AtomicBoolean && !stageAbort.get(),'TRADE_STAGE_ABORTED')
    if (action == 'PRE') {
        def definitions = [REGISTER:['POST','/api/auth/register'],LOGIN:['POST','/api/auth/login'],
            ME:['GET','/api/users/me'],ADDRESS:['POST','/api/users/me/addresses'],
            SKU:['GET','/api/products/skus/'+s.skuId],STOCK:['GET','/api/stock/skus/'+s.skuId],
            ORDER:['POST','/api/orders'],ORDER_REPLAY:['POST','/api/orders'],PAYMENT:['POST','/api/payments'],
            PAYMENT_REPLAY:['POST','/api/payments'],CALLBACK:['POST','/api/payments/callback'],CALLBACK_REPLAY:['POST','/api/payments/callback'],
            POLL_ORDER:['GET','/api/orders/'+s.orderNo],POLL_PAYMENT:['GET','/api/payments/orders/'+s.orderNo],
            POLL_USER:['GET','/api/users/me'],POLL_STOCK:['GET','/api/stock/skus/'+s.skuId]]
        check(definitions.containsKey(stage),'TRADE_STAGE_INVALID')
        boolean polling = stage.startsWith('POLL_')
        long timeout = 15000L
        if (polling) {
            long remaining = s.pollDeadlineNanos - System.nanoTime()
            check(remaining >= 1000000L,'TRADE_POLL_DEADLINE')
            timeout = Math.min(timeout,Math.floorDiv(remaining,1000000L))
        }
        long absoluteDeadline = s.phase=='WARMUP' ? shared.setupDeadlineNanos : shared.t0.get()+120000000000L
        long absoluteRemaining=absoluteDeadline-System.nanoTime()
        check(absoluteRemaining>=1000000L,'TRADE_SLOT_DEADLINE')
        timeout=Math.min(timeout,Math.floorDiv(absoluteRemaining,1000000L))
        check((s.requests[stage] ?: 0)<(polling?60:1) && s.requests.values().sum(0)<252,'TRADE_REQUEST_QUOTA')
        def headers = new HeaderManager();headers.add(new Header('Content-Type','application/json'));headers.add(new Header('Accept','application/json'))
        boolean callback = stage in ['CALLBACK','CALLBACK_REPLAY']
        if (s.token != null && !(stage in ['REGISTER','LOGIN']) && !callback) headers.add(new Header('Authorization','Bearer '+s.token))
        if (callback) headers.add(new Header('X-Internal-Callback-Token',System.getenv('CLOUDMALL_INTERNAL_CALLBACK_TOKEN')))
        String body = null
        if (stage in ['REGISTER','LOGIN']) body = JsonOutput.toJson([username:s.username,password:s.password])
        if (stage == 'ADDRESS') body = JsonOutput.toJson([receiver:'冒烟测试',phone:'13800000000',detailAddress:'隔离测试地址',isDefault:true])
        if (stage in ['ORDER','ORDER_REPLAY']) {
            if (s.orderBody == null) s.orderBody = JsonOutput.toJson([addressId:s.addressId,items:[[skuId:s.skuId,quantity:1]]])
            body = s.orderBody;headers.add(new Header('Idempotency-Key',s.orderKey))
        }
        if (stage in ['PAYMENT','PAYMENT_REPLAY']) {
            if (s.payBody == null) s.payBody = JsonOutput.toJson([orderNo:s.orderNo,payAmount:s.price])
            body = s.payBody;headers.add(new Header('Idempotency-Key',s.payKey))
        }
        if (callback) {
            if (s.callbackBody == null) s.callbackBody = JsonOutput.toJson([payNo:s.payNo,success:true,callbackId:s.callbackId,amount:s.price,userId:s.userId])
            body = s.callbackBody
        }
        sampler.setDomain(vars.get('host'));sampler.setPort((stage in ['STOCK','POLL_STOCK'] ? vars.get('stockPort') : callback ? vars.get('payPort') : vars.get('port')).toInteger())
        sampler.setProtocol('http');sampler.setMethod(definitions[stage][0]);sampler.setPath(definitions[stage][1])
        sampler.setConnectTimeout(Math.min(3000L,timeout).toString());sampler.setResponseTimeout(timeout.toString())
        sampler.setFollowRedirects(false);sampler.setAutoRedirects(false);sampler.setHeaderManager(headers)
        sampler.getArguments().removeAllArguments();sampler.setPostBodyRaw(body != null)
        if (body != null) sampler.addNonEncodedArgument('',body,'')
        s.httpRecordWritten=false
        s.requestSafeHash = stage in ['ORDER','ORDER_REPLAY','PAYMENT','PAYMENT_REPLAY','CALLBACK','CALLBACK_REPLAY'] ? hash(body.getBytes('UTF-8')) : null
        check(stageAbort instanceof AtomicBoolean && !stageAbort.get(),'TRADE_STAGE_ABORTED')
        s.requestStartedNanos=System.nanoTime()
        return
    }
    if (action == 'POST') {
        s.requests[stage] = (s.requests[stage] ?: 0) + 1
        def data
        try { data = new JsonSlurper().parseText(prev.getResponseDataAsString()) }
        catch (Throwable ignored) { abort('TRADE_JSON_'+stage) }
        check(data instanceof Map,'TRADE_ENVELOPE_'+stage)
        boolean callback = stage in ['CALLBACK','CALLBACK_REPLAY']
        boolean accepted = callback && prev.getResponseCode() == '202' && data.code == 'PAY_ORDER_SYNC_UNKNOWN'
        check((prev.getResponseCode() == '200' && data.code == '0') || accepted,'TRADE_HTTP_'+stage)
        def v = data.data
        if (stage == 'REGISTER') { s.userId = id(v.userId);s.slotStatus='IDENTITY_KNOWN' }
        if (stage == 'LOGIN') {
            check(v.token instanceof String && !v.token.isBlank(),'TRADE_LOGIN_TOKEN')
            s.token = v.token;check(id(v.user.userId) == s.userId && v.user.role == 'USER','TRADE_LOGIN_IDENTITY')
        }
        if (stage == 'ME') { check(id(v.userId)==s.userId && v.role=='USER','TRADE_USER_IDENTITY');money(v.balance);s.balanceBefore=v.balance }
        if (stage == 'ADDRESS') s.addressId = id(v.id)
        if (stage == 'SKU') {
            check(id(v.skuId)==s.skuId,'TRADE_SKU_IDENTITY');s.productId=id(v.productId)
            def parsedPrice=money(v.unitPrice)
            check(parsedPrice>0 && parsedPrice<10000 && parsedPrice<=money(s.balanceBefore),'TRADE_SKU_PRICE')
            if (vars.get('expectedProductId')) check(s.productId==id(vars.get('expectedProductId')),'TRADE_SKU_BASELINE')
            if (vars.get('expectedPrice')) check(parsedPrice==money(vars.get('expectedPrice')),'TRADE_PRICE_BASELINE')
            s.price=v.unitPrice
        }
        if (stage == 'STOCK') {
            check(id(v.skuId)==s.skuId && v.availableQuantity instanceof Integer
                && v.availableQuantity>=9971 && v.availableQuantity<=9991,'TRADE_STOCK_BASELINE')
            s.availableBefore=v.availableQuantity
            // Concurrent VUs can reserve before this VU reads its initial stock.
        }
        if (stage in ['ORDER','ORDER_REPLAY','POLL_ORDER']) {
            check(id(v.userId)==s.userId && v.orderNo instanceof String && v.orderNo ==~ /[0-9]{6}[A-Za-z0-9-]+/
                && money(v.payAmount)==money(s.price) && v.items instanceof List && v.items.size()==1
                && id(v.items[0].skuId)==s.skuId && id(v.items[0].productId)==s.productId
                && v.items[0].quantity instanceof Integer && v.items[0].quantity==1,'TRADE_ORDER_FACTS')
            if (stage=='ORDER') { s.orderNo=v.orderNo;s.slotStatus='ORDER_KNOWN' }
            check(v.orderNo==s.orderNo,'TRADE_ORDER_REPLAY_ID')
            if (!stage.startsWith('POLL_')) check(v.status=='PENDING_PAYMENT','TRADE_ORDER_INITIAL_STATE')
            else { check(v.status in ['PENDING_PAYMENT','PAID'],'TRADE_ORDER_TERMINAL');s.finalViews.order=safe(v) }
        }
        if (stage in ['PAYMENT','PAYMENT_REPLAY','POLL_PAYMENT']) {
            check(id(v.userId)==s.userId && v.orderNo==s.orderNo && v.payNo instanceof String && v.payNo ==~ /[0-9a-f]{48}/
                && money(v.amount)==money(s.price),'TRADE_PAYMENT_FACTS')
            if (stage=='PAYMENT') s.payNo=v.payNo
            check(v.payNo==s.payNo,'TRADE_PAYMENT_REPLAY_ID')
            if (!stage.startsWith('POLL_')) check(v.status=='PENDING','TRADE_PAYMENT_INITIAL_STATE')
            else { check(v.status in ['PENDING','DEBITED','ORDER_SYNC_UNKNOWN','SUCCESS'],'TRADE_PAYMENT_TERMINAL');s.finalViews.payment=safe(v) }
        }
        if (callback && !accepted) check(v.payNo==s.payNo && v.orderNo==s.orderNo && id(v.userId)==s.userId && money(v.amount)==money(s.price),'TRADE_CALLBACK_FACTS')
        if (stage=='POLL_USER') { check(id(v.userId)==s.userId,'TRADE_FINAL_USER');money(v.balance);s.finalViews.user=safe(v) }
        if (stage=='POLL_STOCK') { check(id(v.skuId)==s.skuId && v.availableQuantity instanceof Integer,'TRADE_FINAL_STOCK');s.availableFinal=v.availableQuantity;s.finalViews.stock=safe(v) }
        s.httpRecordWritten=true
        s.httpIndex=(s.httpIndex ?: 0)+1
        writeNew(String.format('http-%04d-%s.json',s.httpIndex,stage),[schemaVersion:1,stage:stage,label:prev.getSampleLabel(),
            httpStatus:prev.getResponseCode(),apiCode:data.code,success:true,elapsedMillis:prev.getTime(),phase:s.phase,slotIndex:s.slotIndex,workerIndex:s.workerIndex,
            requestStartedOffsetMillis:s.phase=='MEASUREMENT'?Math.floorDiv(s.requestStartedNanos-shared.t0.get(),1000000L):null,
            requestStartBoundary:'PRE_SAMPLER_DISPATCH_NANO',sampleStartedEpochMillis:prev.getStartTime(),
            userId:s.userId,orderNo:s.orderNo,payNo:s.payNo,skuId:s.skuId,amount:s.price,requestBodySha256:s.requestSafeHash])
        check(stageAbort instanceof AtomicBoolean && !stageAbort.get(),'TRADE_STAGE_ABORTED')
        return
    }
    if (action=='POLL_BEGIN') {
        s.pollDeadlineNanos=s.phase=='WARMUP'?shared.setupDeadlineNanos:shared.t0.get()+120000000000L;s.pollIterations=0;vars.put('trade.poll','true');return
    }
    if (action=='POLL_END') {
        s.pollIterations++
        boolean done=s.finalViews.order?.status=='PAID' && s.finalViews.payment?.status=='SUCCESS'
            && money(s.finalViews.user.balance)==money(s.balanceBefore)-money(s.price)
        check(System.nanoTime()<s.pollDeadlineNanos,'TRADE_POLL_DEADLINE')
        if (done) { vars.put('trade.poll','false');return }
        check(s.pollIterations<60,'TRADE_POLL_LIMIT')
        long remaining=s.pollDeadlineNanos-System.nanoTime();long delay=Math.min(2000L,Math.floorDiv(remaining,1000000L))
        check(delay>0,'TRADE_POLL_DEADLINE');Thread.sleep(delay);return
    }
    if (action=='FINISH') {
        check(vars.get('trade.poll')=='false','TRADE_NOT_CONVERGED');finish(true,null)
        if (vars.getIteration()==4 && shared.t0.get()!=Long.MIN_VALUE) {
            while (System.nanoTime()<shared.t0.get()+60000000000L && !stageAbort.get()) Thread.sleep(25)
            if (!stageAbort.get() && shared.endPublished.compareAndSet(false,true)) {
                byte[] end=JsonOutput.toJson([schemaVersion:1,runId:shared.runId,origin:'JMETER_NANO',measurementWindowMillis:60000,
                    observedEndOffsetMillis:Math.floorDiv(System.nanoTime()-shared.t0.get(),1000000L)]).getBytes('UTF-8')
                Files.write(Paths.get(shared.outputDir).resolve('measurement-end.json'),end,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
            }
        }
        return
    }
    abort('TRADE_ACTION_UNKNOWN')
} catch (TradeSteadyPostAbort stopped) {
    return
} catch (JMeterStopThreadException stopped) {
    throw stopped
} catch (Throwable ignored) {
    abort('TRADE_INTERNAL_'+stage)
} finally {
    if (action=='POST') clearRaw(prev)
}
