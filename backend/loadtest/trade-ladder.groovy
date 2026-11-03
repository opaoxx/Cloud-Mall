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

class TradeLadderPostAbort extends RuntimeException {
    TradeLadderPostAbort() { super('TRADE_POST_ABORT', null, false, false) }
}

// Invoked only by the sibling JMX. No business request is sent from this script.
def parts = Parameters.trim().split(/\s+/)
def action = parts[0]
def stage = parts.length > 1 ? parts[1] : action
def s = vars.getObject('trade.state')
def stageAbort = vars.getObject('trade.stageAbort')
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
    def keys = ['userId','username','balance','id','skuId','productId','orderNo','payNo','status','amount','unitPrice',
                'payAmount','totalAmount','createdAt','quantity','lineAmount','availableQuantity']
    def v = [:]
    keys.each { if (value.containsKey(it)) v[it] = value[it] }
    if (value.items instanceof List) v.items = value.items.collect { item ->
        ['productId','skuId','quantity','unitPrice','lineAmount'].collectEntries { key -> [(key):item[key]] }
    }
    return v
}
def exportState = { ->
    def v = [schemaVersion:1,scope:'JMETER_TRADE_LADDER_THREAD_HTTP_ONLY',profile:'FUNCTIONAL_LADDER',threadCount:s.threadCount,threadIndex:s.threadIndex,stageRun:s.stageRun,iterationCount:1]
    ['runId','username','userId','addressId','skuId','productId','price','balanceBefore','orderNo','payNo',
     'orderKey','payKey','callbackId','availableBefore','availableFinal','stageAvailableBefore','stageAvailableExpectedFinal','pollIterations','requests','finalViews'].each { v[it] = s[it] }
    v
}
def finish = { boolean ok, String code ->
    if (s != null && !s.finished) {
        s.finished = true
        def v = exportState()
        v.httpPassed = ok
        v.status = ok ? 'HTTP_FUNCTIONAL_OBSERVED_PENDING_SQL' : 'HTTP_FUNCTIONAL_FAILED_OR_UNKNOWN'
        v.errorCode = code
        v.completedEpochMillis = System.currentTimeMillis()
        v.sqlConsistencyProven = false
        writeNew('finish.json', v)
    }
}
def abort = { String code ->
    if (stageAbort instanceof AtomicBoolean) stageAbort.set(true)
    vars.put('trade.abort','true')
    vars.put('trade.poll','false')
    if (result != null) {
        result.setSuccessful(false)
        result.setResponseMessage(code)
        def failure = new AssertionResult('trade-ladder')
        failure.setFailure(true); failure.setFailureMessage(code); result.addAssertionResult(failure)
    }
    try { finish(false, code) } catch (Throwable ignored) { /* Never overwrite a foreign/old finish file. */ }
    ctx.getEngine().stopTest(false)
    ctx.getThread().stop()
    // PostProcessor must return normally so the failed HTTP sample reaches JTL.
    if (action == 'POST') throw new TradeLadderPostAbort()
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
        String stageRun = vars.get('runId')
        check(stageRun != null && stageRun ==~ /ld_[0-9a-f]{10}_s[123]/,'TRADE_STAGE_RUN_ID_INVALID')
        def candidateGate = new AtomicBoolean(false)
        def previousGate = props.putIfAbsent('trade.ladder.abort.'+stageRun, candidateGate)
        stageAbort = previousGate == null ? candidateGate : previousGate
        check(stageAbort instanceof AtomicBoolean,'TRADE_STAGE_ABORT_STATE_INVALID')
        vars.putObject('trade.stageAbort',stageAbort)
        String declaredThreads = vars.get('threads')
        check(declaredThreads in ['1','2','5'] && vars.getIteration()==1,'TRADE_LADDER_SHAPE_INVALID')
        int expectedThreads = declaredThreads.toInteger()
        int stageThreads = ['1':1,'2':2,'3':5][stageRun[-1]]
        check(expectedThreads==stageThreads && ctx.getThreadGroup().getNumThreads()==expectedThreads
            && ctx.getThreadNum()>=0 && ctx.getThreadNum()<expectedThreads,'TRADE_LADDER_SHAPE_INVALID')
        check(!props.containsKey('cloudmall.loops') || props.getProperty('cloudmall.loops')=='1','TRADE_LADDER_SHAPE_INVALID')
        check(vars.get('profile') == 'FUNCTIONAL_LADDER', 'TRADE_PROFILE_INVALID')
        check(!stageAbort.get(),'TRADE_STAGE_ABORTED')
        ['response_data','response_data.on_error','samplerData','requestHeaders','responseHeaders'].each {
            check(!props.getProperty('jmeter.save.saveservice.'+it,'false').equalsIgnoreCase('true'),'TRADE_UNSAFE_JTL_CONFIG')
        }
        check(props.getProperty('sample_variables','').trim().isEmpty(),'TRADE_UNSAFE_SAMPLE_VARIABLES')
        check(System.getenv('JMETER_TRADE_PASSWORD') != null && !System.getenv('JMETER_TRADE_PASSWORD').isBlank(),'TRADE_PASSWORD_ENV_MISSING')
        check(System.getenv('CLOUDMALL_INTERNAL_CALLBACK_TOKEN') != null && !System.getenv('CLOUDMALL_INTERNAL_CALLBACK_TOKEN').isBlank(),'TRADE_CALLBACK_TOKEN_ENV_MISSING')
        int threadIndex = ctx.getThreadNum()+1
        String run = stageRun+'_u'+threadIndex
        check(('jmeter_trade_'+run).length()<=32,'TRADE_USERNAME_LENGTH')
        check(vars.get('host') in ['localhost','127.0.0.1'],'TRADE_HOST_INVALID')
        def directory = Paths.get(vars.get('outputDir') ?: '')
        check(directory.isAbsolute() && Files.isDirectory(directory) && !Files.isSymbolicLink(directory),'TRADE_OUTPUT_DIR_INVALID')
        ['port','stockPort','payPort'].each { check(vars.get(it) ==~ /[1-9][0-9]{0,4}/ && vars.get(it).toInteger() <= 65535,'TRADE_PORT_INVALID') }
        check(vars.get('expectedAvailable') != null && vars.get('expectedAvailable') ==~ /(?:0|[1-9][0-9]*)/,'TRADE_STAGE_STOCK_REQUIRED')
        int stageAvailable = new BigInteger(vars.get('expectedAvailable')).intValueExact()
        check(stageAvailable>=expectedThreads,'TRADE_STAGE_STOCK_INSUFFICIENT')
        def threadDirectory = directory.normalize().resolve('u'+threadIndex)
        Files.createDirectory(threadDirectory)
        s = [outputDir:threadDirectory.toString(),runId:run,stageRun:stageRun,threadIndex:threadIndex,threadCount:expectedThreads,
            username:'jmeter_trade_'+run,stageAvailableBefore:stageAvailable,stageAvailableExpectedFinal:stageAvailable-expectedThreads,
            skuId:id(vars.get('skuId')),orderKey:'jmeter-order-'+run,payKey:'jmeter-pay-'+run,
            callbackId:'jmeter-callback-'+run,requests:[:],finalViews:[:],pollIterations:0,finished:false]
        s.password = hash((System.getenv('JMETER_TRADE_PASSWORD')+':'+run).getBytes('UTF-8')).substring(0,32)
        vars.putObject('trade.state',s);vars.put('trade.abort','false')
        writeNew('run.once',[schemaVersion:1,profile:'FUNCTIONAL_LADDER',stageRun:stageRun,runId:run,threadIndex:threadIndex,threadCount:expectedThreads,skuId:s.skuId,createdEpochMillis:System.currentTimeMillis()])
        writeNew('run-state.json',exportState()); result.setResponseCode('200');result.setSuccessful(true);return
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
        s.requestSafeHash = stage in ['ORDER','ORDER_REPLAY','PAYMENT','PAYMENT_REPLAY','CALLBACK','CALLBACK_REPLAY'] ? hash(body.getBytes('UTF-8')) : null
        check(stageAbort instanceof AtomicBoolean && !stageAbort.get(),'TRADE_STAGE_ABORTED')
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
        if (stage == 'REGISTER') s.userId = id(v.userId)
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
                && v.availableQuantity>=s.stageAvailableExpectedFinal && v.availableQuantity<=s.stageAvailableBefore,'TRADE_STOCK_BASELINE')
            s.availableBefore=v.availableQuantity
            // Concurrent VUs can reserve before this VU reads its initial stock.
        }
        if (stage in ['ORDER','ORDER_REPLAY','POLL_ORDER']) {
            check(id(v.userId)==s.userId && v.orderNo instanceof String && v.orderNo ==~ /[0-9]{6}[A-Za-z0-9-]+/
                && money(v.payAmount)==money(s.price) && v.items instanceof List && v.items.size()==1
                && id(v.items[0].skuId)==s.skuId && id(v.items[0].productId)==s.productId
                && v.items[0].quantity instanceof Integer && v.items[0].quantity==1,'TRADE_ORDER_FACTS')
            if (stage=='ORDER') s.orderNo=v.orderNo
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
        s.httpIndex=(s.httpIndex ?: 0)+1
        writeNew(String.format('http-%04d-%s.json',s.httpIndex,stage),[schemaVersion:1,stage:stage,label:prev.getSampleLabel(),
            httpStatus:prev.getResponseCode(),apiCode:data.code,success:true,elapsedMillis:prev.getTime(),
            userId:s.userId,orderNo:s.orderNo,payNo:s.payNo,skuId:s.skuId,amount:s.price,requestBodySha256:s.requestSafeHash])
        check(stageAbort instanceof AtomicBoolean && !stageAbort.get(),'TRADE_STAGE_ABORTED')
        return
    }
    if (action=='POLL_BEGIN') {
        s.pollDeadlineNanos=System.nanoTime()+120000000000L;s.pollIterations=0;vars.put('trade.poll','true');return
    }
    if (action=='POLL_END') {
        s.pollIterations++
        boolean done=s.finalViews.order?.status=='PAID' && s.finalViews.payment?.status=='SUCCESS'
            && money(s.finalViews.user.balance)==money(s.balanceBefore)-money(s.price) && s.availableFinal==s.stageAvailableExpectedFinal
        check(System.nanoTime()<s.pollDeadlineNanos,'TRADE_POLL_DEADLINE')
        if (done) { vars.put('trade.poll','false');return }
        check(s.pollIterations<60,'TRADE_POLL_LIMIT')
        long remaining=s.pollDeadlineNanos-System.nanoTime();long delay=Math.min(2000L,Math.floorDiv(remaining,1000000L))
        check(delay>0,'TRADE_POLL_DEADLINE');Thread.sleep(delay);return
    }
    if (action=='FINISH') { check(vars.get('trade.poll')=='false','TRADE_NOT_CONVERGED');finish(true,null);return }
    abort('TRADE_ACTION_UNKNOWN')
} catch (TradeLadderPostAbort stopped) {
    return
} catch (JMeterStopThreadException stopped) {
    throw stopped
} catch (Throwable ignored) {
    abort('TRADE_INTERNAL_'+stage)
} finally {
    if (action=='POST') clearRaw(prev)
}
