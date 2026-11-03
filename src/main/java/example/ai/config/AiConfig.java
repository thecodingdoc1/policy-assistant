package example.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

@Configuration
public class AiConfig
{
    public record PurchaseOrderRequest(String poNumber){}

    @Bean
    @Description("Get the current status of a SAP Purchase Order")
    public Function<PurchaseOrderRequest, String> getPurchaseOrderStatus() {
        return request -> {
            if ("PO-123".equals(request.poNumber))
            {
                return "Approved";
            }
            return "Denied";
        };
    }
}
