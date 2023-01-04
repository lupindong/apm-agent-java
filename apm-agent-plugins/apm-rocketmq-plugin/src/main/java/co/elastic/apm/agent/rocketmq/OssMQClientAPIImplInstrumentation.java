//package co.elastic.apm.agent.rocketmq;
//
//import co.elastic.apm.agent.bci.TracerAwareInstrumentation;
//import net.bytebuddy.description.method.MethodDescription;
//import net.bytebuddy.description.type.TypeDescription;
//import net.bytebuddy.matcher.ElementMatcher;
//
//import java.util.Collection;
//import java.util.Collections;
//
//import static net.bytebuddy.matcher.ElementMatchers.*;
//
///**
// * 开源版MQClientAPIImpl
// */
//public class OssMQClientAPIImplInstrumentation extends TracerAwareInstrumentation {
//
//    @Override
//    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
//        return named("org.apache.dubbo.monitor.support.MonitorFilter");
//    }
//
//    /**
//     * {@link org.apache.dubbo.monitor.support.MonitorFilter#invoke(Invoker, Invocation)}
//     */
//    @Override
//    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
//        return named("invoke")
//            .and(takesArgument(0, named("org.apache.dubbo.rpc.Invoker")))
//            .and(takesArgument(1, named("org.apache.dubbo.rpc.Invocation")))
//            // makes sure we only instrument Dubbo 2.7.3+ which introduces this method
//            .and(returns(hasSuperType(named("org.apache.dubbo.rpc.Result"))
//                .and(declaresMethod(named("whenCompleteWithContext")
//                    .and(takesArgument(0, named("java.util.function.BiConsumer")))))));
//    }
//
//    @Override
//    public String getAdviceClassName() {
//        return "co.elastic.apm.agent.dubbo3.advice.ApacheMonitorFilterAdvice";
//    }
//
//    @Override
//    public Collection<String> getInstrumentationGroupNames() {
//        return Collections.singletonList("star-rocketmq");
//    }
//
//}
