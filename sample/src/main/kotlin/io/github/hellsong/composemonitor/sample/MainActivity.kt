package io.github.hellsong.composemonitor.sample

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.hellsong.composemonitor.helper.ComposableMonitor

class MainActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            var text by remember { mutableStateOf("haha!") }
            var count by remember { mutableStateOf(0) }
            var dataList by remember { mutableStateOf(DataList().apply {
                list.add(Data())
            }) }
            var data by remember { mutableStateOf(Data()) }

            Column(Modifier.safeDrawingPadding()) {

                Button(onClick = {
                    count++
                    text = "haha! count = ${count + dataList.list[0].id}"
                }) {
                    Text("Click Me!")
                }
                MainScreen(text)

                DataTest(id = data.id)
            }
        }

        test(100)
        sum(1, 2)
    }

    private fun test(time: Long) {
        Thread.sleep(time)
    }

    private fun sum(i: Int, j: Int): Int {
        return i + j
    }
}

@Preview
@Composable
fun MainScreenPreview() {
    MainScreen("123")
}

@ComposableMonitor
@Composable
fun MainScreen(text: String) {
    var text2 by remember { mutableStateOf("Hello World!") }
    var count by remember { mutableStateOf(0) }

    Text("$text $text2!")
    Button(onClick = {
        count++
        text2 = "Hello World! count = $count"
    }) {
        Text("Click Me!")
    }

}

@ComposableMonitor
@Composable
fun DataTest(id: Long) {
    Text("${id}")
}

class DataList{
    var list: MutableList<Data> = mutableListOf()
}

class Data {
    var id: Long = Long.MAX_VALUE - 100
    var name: String = ""
}